package com.test;

import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.Disk;
import com.microsoft.azure.management.compute.Snapshot;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.management.storage.StorageAccountSkuType;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.CopyStatus;

import java.io.File;
import java.net.URI;
import java.util.UUID;

/**
 * Hello world!
 *
 */
public class App {
    public static String randomString(String prefix, int len) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, len - prefix.length());
    }

    public static void main(String[] args) throws Exception {
        final String resourceGroupName = randomString("rg", 8);
        final String diskName = randomString("d", 8);
        final String snapshotName = randomString("s", 8);
        final String storageAccountName = randomString("sg", 8);
        final String containerName = randomString("c", 8);
        final String blobName = "os.vhd";
        final String dstDiskName = randomString("d", 8);
        final Region region1 = Region.US_WEST;
        final Region region2 = Region.ASIA_SOUTHEAST;

        final File credFile = new File(System.getenv("AZURE_AUTH_LOCATION"));

        ApplicationTokenCredentials credentials = ApplicationTokenCredentials.fromFile(credFile);
        Azure azure = Azure.authenticate(credentials).withDefaultSubscription();

        try {

            Disk disk = azure.disks().define(diskName)
                    .withRegion(region1)
                    .withNewResourceGroup(resourceGroupName)
                    .withData()
                    .withSizeInGB(16)
                    .create();

            Snapshot snapshot = azure.snapshots().define(snapshotName)
                    .withRegion(region1)
                    .withExistingResourceGroup(resourceGroupName)
                    .withDataFromDisk(disk)
                    .create();

            String snapshotURI = snapshot.grantAccess(3600);

            StorageAccount storageAccount = azure.storageAccounts().define(storageAccountName)
                    .withRegion(region2)
                    .withExistingResourceGroup(resourceGroupName)
                    .withSku(StorageAccountSkuType.PREMIUM_LRS)
                    .create();

            final String storageConnectionString = String.format(
                    "DefaultEndpointsProtocol=https;" +
                            "AccountName=%s;" +
                            "AccountKey=%s", storageAccount.name(), storageAccount.getKeys().get(0).value());

            CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(storageConnectionString);
            CloudBlobClient blobClient = cloudStorageAccount.createCloudBlobClient();

            CloudBlobContainer container = blobClient.getContainerReference(containerName);
            container.createIfNotExists();
            CloudBlockBlob blob = container.getBlockBlobReference(blobName);
            blob.startCopy(new URI(snapshotURI));

            CopyStatus status = blob.getCopyState().getStatus();
            while (status.equals(CopyStatus.PENDING)) {
                System.out.printf("Wait for copy success, status = %s\n", status);
                Thread.sleep(30 * 1000);
                blob.exists();
                status = blob.getCopyState().getStatus();
            }

            if (!status.equals(CopyStatus.SUCCESS)) {
                System.err.printf("Unsuccess copy, status = %s\n", status);
                return;
            }

            Disk dstDisk = azure.disks().define(dstDiskName)
                    .withRegion(region2)
                    .withExistingResourceGroup(resourceGroupName)
                    .withLinuxFromVhd(blob.getUri().toString())
                    .withStorageAccount(storageAccount)
                    .create();

            azure.resourceGroups().beginDeleteByName(resourceGroupName);

        } catch (Exception e) {
            azure.resourceGroups().beginDeleteByName(resourceGroupName);
            e.printStackTrace();
        }
    }
}
