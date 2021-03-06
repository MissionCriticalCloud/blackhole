package com.cloud.network.guru;

import com.cloud.db.repository.ZoneRepository;
import com.cloud.dc.DataCenterIpAddressVO;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.legacymodel.dc.Pod;
import com.cloud.legacymodel.exceptions.CloudRuntimeException;
import com.cloud.legacymodel.exceptions.InsufficientAddressCapacityException;
import com.cloud.legacymodel.exceptions.InsufficientVirtualNetworkCapacityException;
import com.cloud.legacymodel.network.Network;
import com.cloud.legacymodel.network.Nic.ReservationStrategy;
import com.cloud.legacymodel.user.Account;
import com.cloud.legacymodel.utils.Pair;
import com.cloud.model.enumeration.BroadcastDomainType;
import com.cloud.model.enumeration.DHCPMode;
import com.cloud.model.enumeration.IpAddressFormat;
import com.cloud.model.enumeration.TrafficType;
import com.cloud.network.NetworkProfile;
import com.cloud.network.StorageNetworkManager;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PodBasedNetworkGuru extends AdapterBase implements NetworkGuru {
    private static final Logger s_logger = LoggerFactory.getLogger(PodBasedNetworkGuru.class);
    private static final TrafficType[] TrafficTypes = {TrafficType.Management};
    @Inject
    DataCenterIpAddressDao _ipAllocDao;
    @Inject
    ZoneRepository _zoneRepository;
    @Inject
    StorageNetworkManager _sNwMgr;

    protected PodBasedNetworkGuru() {
        super();
    }

    @Override
    public Network design(final NetworkOffering offering, final DeploymentPlan plan, final Network userSpecified, final Account owner) {
        final TrafficType type = offering.getTrafficType();

        if (!isMyTrafficType(type)) {
            return null;
        }

        final NetworkVO config =
                new NetworkVO(type, DHCPMode.Static, BroadcastDomainType.Native, offering.getId(), Network.State.Setup, plan.getDataCenterId(),
                        plan.getPhysicalNetworkId(), offering.getRedundantRouter());
        return config;
    }

    @Override
    public Network implement(final Network config, final NetworkOffering offering, final DeployDestination destination, final ReservationContext context)
            throws InsufficientVirtualNetworkCapacityException {
        return config;
    }

    @Override
    public NicProfile allocate(final Network config, NicProfile nic, final VirtualMachineProfile vm) throws InsufficientVirtualNetworkCapacityException,
            InsufficientAddressCapacityException {
        final TrafficType trafficType = config.getTrafficType();
        assert trafficType == TrafficType.Management || trafficType == TrafficType.Storage : "Well, I can't take care of this config now can I? " + config;

        if (nic != null) {
            if (nic.getRequestedIPv4() != null) {
                throw new CloudRuntimeException("Does not support custom ip allocation at this time: " + nic);
            }
            nic.setReservationStrategy(nic.getIPv4Address() != null ? ReservationStrategy.Create : ReservationStrategy.Start);
        } else {
            nic = new NicProfile(ReservationStrategy.Start, null, null, null, null);
        }

        return nic;
    }

    @Override
    public void reserve(final NicProfile nic, final Network config, final VirtualMachineProfile vm, final DeployDestination dest, final ReservationContext context)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {

        final Pod pod = dest.getPod();

        Pair<String, Long> ip = null;

        _ipAllocDao.releaseIpAddress(nic.getId());
        final DataCenterIpAddressVO vo = _ipAllocDao.takeIpAddress(dest.getZone().getId(), dest.getPod().getId(), nic.getId(), context.getReservationId());

        if (vo != null) {
            ip = new Pair<>(vo.getIpAddress(), vo.getMacAddress());
        }

        if (ip == null) {
            throw new InsufficientAddressCapacityException("Unable to get a management ip address", Pod.class, pod.getId());
        }

        nic.setIPv4Address(ip.first());
        nic.setMacAddress(NetUtils.long2Mac(NetUtils.createSequenceBasedMacAddress(ip.second())));
        nic.setIPv4Gateway(pod.getGateway());
        nic.setFormat(IpAddressFormat.Ip4);
        final String netmask = NetUtils.getCidrNetmask(pod.getCidrSize());
        nic.setIPv4Netmask(netmask);
        nic.setBroadcastType(BroadcastDomainType.Native);
        nic.setBroadcastUri(null);
        nic.setIsolationUri(null);

        s_logger.debug("Allocated a nic " + nic + " for " + vm);
    }

    @Override
    public boolean release(final NicProfile nic, final VirtualMachineProfile vm, final String reservationId) {
        _ipAllocDao.releaseIpAddress(nic.getId(), nic.getReservationId());

        nic.deallocate();

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Released nic: " + nic);
        }

        return true;
    }

    @Override
    public void deallocate(final Network config, final NicProfile nic, final VirtualMachineProfile vm) {
    }

    @Override
    public void updateNicProfile(final NicProfile profile, final Network network) {
    }

    @Override
    public void shutdown(final NetworkProfile config, final NetworkOffering offering) {
    }

    @Override
    public boolean trash(final Network config, final NetworkOffering offering) {
        return true;
    }

    @Override
    public void updateNetworkProfile(final NetworkProfile networkProfile) {
    }

    @Override
    public TrafficType[] getSupportedTrafficType() {
        return TrafficTypes;
    }

    @Override
    public boolean isMyTrafficType(final TrafficType type) {
        for (final TrafficType t : TrafficTypes) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }
}
