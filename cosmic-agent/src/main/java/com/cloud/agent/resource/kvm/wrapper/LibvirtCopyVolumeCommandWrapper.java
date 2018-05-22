package com.cloud.agent.resource.kvm.wrapper;

import com.cloud.agent.resource.kvm.LibvirtComputingResource;
import com.cloud.agent.resource.kvm.storage.KvmPhysicalDisk;
import com.cloud.agent.resource.kvm.storage.KvmStoragePool;
import com.cloud.agent.resource.kvm.storage.KvmStoragePoolManager;
import com.cloud.common.request.ResourceWrapper;
import com.cloud.legacymodel.communication.answer.Answer;
import com.cloud.legacymodel.communication.answer.CopyVolumeAnswer;
import com.cloud.legacymodel.communication.command.CopyVolumeCommand;
import com.cloud.legacymodel.exceptions.CloudRuntimeException;
import com.cloud.legacymodel.to.StorageFilerTO;

import java.io.File;

@ResourceWrapper(handles = CopyVolumeCommand.class)
public final class LibvirtCopyVolumeCommandWrapper
        extends LibvirtCommandWrapper<CopyVolumeCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final CopyVolumeCommand command, final LibvirtComputingResource libvirtComputingResource) {

        final boolean copyToSecondary = command.toSecondaryStorage();
        String volumePath = command.getVolumePath();
        final StorageFilerTO pool = command.getPool();
        final String secondaryStorageUrl = command.getSecondaryStorageURL();
        KvmStoragePool secondaryStoragePool = null;
        KvmStoragePool primaryPool = null;

        final KvmStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();
        try {
            try {
                primaryPool = storagePoolMgr.getStoragePool(pool.getType(), pool.getUuid());
            } catch (final CloudRuntimeException e) {
                if (e.getMessage().contains("not found")) {
                    primaryPool = storagePoolMgr.createStoragePool(pool.getUuid(), pool.getHost(), pool.getPort(), pool.getPath(),
                            pool.getUserInfo(), pool.getType());
                } else {
                    return new CopyVolumeAnswer(command, false, e.getMessage(), null, null);
                }
            }

            final LibvirtUtilitiesHelper libvirtUtilitiesHelper = libvirtComputingResource.getLibvirtUtilitiesHelper();
            final String volumeName = libvirtUtilitiesHelper.generateUuidName();

            if (copyToSecondary) {
                final String destVolumeName = volumeName + ".qcow2";
                final KvmPhysicalDisk volume = primaryPool.getPhysicalDisk(command.getVolumePath());
                final String volumeDestPath = "/volumes/" + command.getVolumeId() + File.separator;

                secondaryStoragePool = storagePoolMgr.getStoragePoolByUri(secondaryStorageUrl);
                secondaryStoragePool.createFolder(volumeDestPath);
                storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
                secondaryStoragePool = storagePoolMgr.getStoragePoolByUri(secondaryStorageUrl + volumeDestPath);
                storagePoolMgr.copyPhysicalDisk(volume, destVolumeName, secondaryStoragePool, 0);

                return new CopyVolumeAnswer(command, true, null, null, volumeName);
            } else {
                volumePath = "/volumes/" + command.getVolumeId() + File.separator;
                secondaryStoragePool = storagePoolMgr.getStoragePoolByUri(secondaryStorageUrl + volumePath);

                final KvmPhysicalDisk volume = secondaryStoragePool.getPhysicalDisk(command.getVolumePath() + ".qcow2");
                storagePoolMgr.copyPhysicalDisk(volume, volumeName, primaryPool, 0);

                return new CopyVolumeAnswer(command, true, null, null, volumeName);
            }
        } catch (final CloudRuntimeException e) {
            return new CopyVolumeAnswer(command, false, e.toString(), null, null);
        } finally {
            if (secondaryStoragePool != null) {
                storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
            }
        }
    }
}
