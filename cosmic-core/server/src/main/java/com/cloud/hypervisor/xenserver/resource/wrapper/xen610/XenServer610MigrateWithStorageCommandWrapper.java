package com.cloud.hypervisor.xenserver.resource.wrapper.xen610;

import com.cloud.common.request.CommandWrapper;
import com.cloud.common.request.ResourceWrapper;
import com.cloud.hypervisor.xenserver.resource.XenServer610Resource;
import com.cloud.hypervisor.xenserver.resource.XsHost;
import com.cloud.hypervisor.xenserver.resource.XsLocalNetwork;
import com.cloud.legacymodel.communication.answer.Answer;
import com.cloud.legacymodel.communication.answer.MigrateWithStorageAnswer;
import com.cloud.legacymodel.communication.command.MigrateWithStorageCommand;
import com.cloud.legacymodel.exceptions.CloudRuntimeException;
import com.cloud.legacymodel.to.NicTO;
import com.cloud.legacymodel.to.StorageFilerTO;
import com.cloud.legacymodel.to.VirtualMachineTO;
import com.cloud.legacymodel.to.VolumeObjectTO;
import com.cloud.legacymodel.to.VolumeTO;
import com.cloud.legacymodel.utils.Pair;
import com.cloud.model.enumeration.TrafficType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xensource.xenapi.Connection;
import com.xensource.xenapi.Host;
import com.xensource.xenapi.Network;
import com.xensource.xenapi.SR;
import com.xensource.xenapi.Task;
import com.xensource.xenapi.Types;
import com.xensource.xenapi.VDI;
import com.xensource.xenapi.VIF;
import com.xensource.xenapi.VM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ResourceWrapper(handles = MigrateWithStorageCommand.class)
public final class XenServer610MigrateWithStorageCommandWrapper extends CommandWrapper<MigrateWithStorageCommand, Answer, XenServer610Resource> {

    private static final Logger s_logger = LoggerFactory.getLogger(XenServer610MigrateWithStorageCommandWrapper.class);

    @Override
    public Answer execute(final MigrateWithStorageCommand command, final XenServer610Resource xenServer610Resource) {
        final Connection connection = xenServer610Resource.getConnection();
        final VirtualMachineTO vmSpec = command.getVirtualMachine();
        final List<Pair<VolumeTO, StorageFilerTO>> volumeToFiler = command.getVolumeToFilerAsList();
        final String vmName = vmSpec.getName();
        Task task = null;

        final XsHost xsHost = xenServer610Resource.getHost();
        final String uuid = xsHost.getUuid();
        try {
            xenServer610Resource.prepareISO(connection, vmName, null, null);

            // Get the list of networks and recreate VLAN, if required.
            for (final NicTO nicTo : vmSpec.getNics()) {
                xenServer610Resource.getNetwork(connection, nicTo);
            }

            final Map<String, String> other = new HashMap<>();
            other.put("live", "true");

            final XsLocalNetwork nativeNetworkForTraffic = xenServer610Resource.getNativeNetworkForTraffic(connection, TrafficType.Storage, null);
            final Network networkForSm = nativeNetworkForTraffic.getNetwork();

            // Create the vif map. The vm stays in the same cluster so we have to pass an empty vif map.
            final Map<VIF, Network> vifMap = new HashMap<>();
            final Map<VDI, SR> vdiMap = new HashMap<>();
            for (final Pair<VolumeTO, StorageFilerTO> entry : volumeToFiler) {
                final VolumeTO volume = entry.first();
                final StorageFilerTO sotrageFiler = entry.second();
                vdiMap.put(xenServer610Resource.getVDIbyUuid(connection, volume.getPath()), xenServer610Resource.getStorageRepository(connection, sotrageFiler.getUuid()));
            }

            // Get the vm to migrate.
            final Set<VM> vms = VM.getByNameLabel(connection, vmSpec.getName());
            final VM vmToMigrate = vms.iterator().next();

            // Check migration with storage is possible.
            final Host host = Host.getByUuid(connection, uuid);
            final Map<String, String> token = host.migrateReceive(connection, networkForSm, other);
            task = vmToMigrate.assertCanMigrateAsync(connection, token, true, vdiMap, vifMap, other);
            try {
                // poll every 1 seconds
                final long timeout = xenServer610Resource.getMigrateWait() * 1000L;
                xenServer610Resource.waitForTask(connection, task, 1000, timeout);
                xenServer610Resource.checkForSuccess(connection, task);
            } catch (final Types.HandleInvalid e) {
                s_logger.error("Error while checking if vm " + vmName + " can be migrated to the destination host " + host, e);
                throw new CloudRuntimeException("Error while checking if vm " + vmName + " can be migrated to the " + "destination host " + host, e);
            }

            // Migrate now.
            task = vmToMigrate.migrateSendAsync(connection, token, true, vdiMap, vifMap, other);
            try {
                // poll every 1 seconds.
                final long timeout = xenServer610Resource.getMigrateWait() * 1000L;
                xenServer610Resource.waitForTask(connection, task, 1000, timeout);
                xenServer610Resource.checkForSuccess(connection, task);
            } catch (final Types.HandleInvalid e) {
                s_logger.error("Error while migrating vm " + vmName + " to the destination host " + host, e);
                throw new CloudRuntimeException("Error while migrating vm " + vmName + " to the destination host " + host, e);
            }

            // Volume paths would have changed. Return that information.
            final List<VolumeObjectTO> volumeToList = xenServer610Resource.getUpdatedVolumePathsOfMigratedVm(connection, vmToMigrate, vmSpec.getDisks());
            vmToMigrate.setAffinity(connection, host);
            return new MigrateWithStorageAnswer(command, volumeToList);
        } catch (final Exception e) {
            s_logger.warn("Catch Exception " + e.getClass().getName() + ". Storage motion failed due to " + e.toString(), e);
            return new MigrateWithStorageAnswer(command, e);
        } finally {
            if (task != null) {
                try {
                    task.destroy(connection);
                } catch (final Exception e) {
                    s_logger.debug("Unable to destroy task " + task.toString() + " on host " + uuid + " due to " + e.toString());
                }
            }
        }
    }
}
