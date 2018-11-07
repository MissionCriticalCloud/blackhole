package com.cloud.network;

import com.cloud.api.command.admin.network.DedicateGuestVlanRangeCmd;
import com.cloud.api.command.admin.network.ListDedicatedGuestVlanRangesCmd;
import com.cloud.api.command.user.network.CreateNetworkCmd;
import com.cloud.api.command.user.network.ListNetworksCmd;
import com.cloud.api.command.user.network.RestartNetworkCmd;
import com.cloud.api.command.user.vm.ListNicsCmd;
import com.cloud.legacymodel.exceptions.ConcurrentOperationException;
import com.cloud.legacymodel.exceptions.InsufficientAddressCapacityException;
import com.cloud.legacymodel.exceptions.InsufficientCapacityException;
import com.cloud.legacymodel.exceptions.ResourceAllocationException;
import com.cloud.legacymodel.exceptions.ResourceUnavailableException;
import com.cloud.legacymodel.network.Network;
import com.cloud.legacymodel.network.Network.Service;
import com.cloud.legacymodel.network.Nic;
import com.cloud.legacymodel.user.Account;
import com.cloud.legacymodel.user.User;
import com.cloud.legacymodel.utils.Pair;
import com.cloud.model.enumeration.TrafficType;
import com.cloud.offering.NetworkOffering;
import com.cloud.vm.NicSecondaryIp;

import java.util.List;
import java.util.Map;

/**
 * The NetworkService interface is the "public" api to entities that make requests to the orchestration engine
 * Such entities are usually the admin and end-user API.
 */
public interface NetworkService {

    List<? extends Network> getIsolatedNetworksOwnedByAccountInZone(long zoneId, Account owner);

    IpAddress allocateIP(Account ipOwner, long zoneId, Long networkId, Boolean displayIp) throws ResourceAllocationException, InsufficientAddressCapacityException,
            ConcurrentOperationException;

    boolean releaseIpAddress(long ipAddressId) throws InsufficientAddressCapacityException;

    Network createGuestNetwork(CreateNetworkCmd cmd) throws InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException;

    Pair<List<? extends Network>, Integer> searchForNetworks(ListNetworksCmd cmd);

    boolean deleteNetwork(long networkId, boolean forced);

    boolean restartNetwork(RestartNetworkCmd cmd, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    Network getNetwork(long networkId);

    Network getNetwork(String networkUuid);

    IpAddress getIp(long id);

    Network updateGuestNetwork(long networkId, String name, String displayText, Account callerAccount, User callerUser, String domainSuffix, Long networkOfferingId,
                               Boolean changeCidr, String guestVmCidr, Boolean displayNetwork, String newUUID, String dns1, String dns2, String ipExclusionList,
                               String dhcpTftpServer, String dhcpBootfileName);

    PhysicalNetwork createPhysicalNetwork(Long zoneId, String vnetRange, String networkSpeed, List<String> isolationMethods, String broadcastDomainRange, Long domainId,
                                          List<String> tags, String name);

    Pair<List<? extends PhysicalNetwork>, Integer> searchPhysicalNetworks(Long id, Long zoneId, String keyword, Long startIndex, Long pageSize, String name);

    PhysicalNetwork updatePhysicalNetwork(Long id, String networkSpeed, List<String> tags, String newVnetRangeString, String state);

    boolean deletePhysicalNetwork(Long id);

    List<? extends Service> listNetworkServices(String providerName);

    PhysicalNetworkServiceProvider addProviderToPhysicalNetwork(Long physicalNetworkId, String providerName, Long destinationPhysicalNetworkId,
                                                                List<String> enabledServices);

    Pair<List<? extends PhysicalNetworkServiceProvider>, Integer> listNetworkServiceProviders(Long physicalNetworkId, String name, String state, Long startIndex,
                                                                                              Long pageSize);

    PhysicalNetworkServiceProvider updateNetworkServiceProvider(Long id, String state, List<String> enabledServices);

    boolean deleteNetworkServiceProvider(Long id) throws ConcurrentOperationException, ResourceUnavailableException;

    PhysicalNetwork getPhysicalNetwork(Long physicalNetworkId);

    PhysicalNetwork getCreatedPhysicalNetwork(Long physicalNetworkId);

    PhysicalNetworkServiceProvider getPhysicalNetworkServiceProvider(Long providerId);

    PhysicalNetworkServiceProvider getCreatedPhysicalNetworkServiceProvider(Long providerId);

    long findPhysicalNetworkId(long zoneId, String tag, TrafficType trafficType);

    PhysicalNetworkTrafficType addTrafficTypeToPhysicalNetwork(Long physicalNetworkId, String trafficType, String isolationMethod, String xenLabel, String kvmLabel, String vlan);

    PhysicalNetworkTrafficType getPhysicalNetworkTrafficType(Long id);

    boolean deletePhysicalNetworkTrafficType(Long id);

    GuestVlan dedicateGuestVlanRange(DedicateGuestVlanRangeCmd cmd);

    Pair<List<? extends GuestVlan>, Integer> listDedicatedGuestVlanRanges(ListDedicatedGuestVlanRangesCmd cmd);

    boolean releaseDedicatedGuestVlanRange(Long dedicatedGuestVlanRangeId);

    Pair<List<? extends PhysicalNetworkTrafficType>, Integer> listTrafficTypes(Long physicalNetworkId);

    Network getExclusiveGuestNetwork(long zoneId);

    /**
     * @param networkId
     * @return
     * @throws ConcurrentOperationException
     * @throws ResourceUnavailableException
     * @throws ResourceAllocationException
     * @throws InsufficientAddressCapacityException
     */
    IpAddress associateIPToNetwork(long ipId, long networkId) throws InsufficientAddressCapacityException, ResourceAllocationException, ResourceUnavailableException,
            ConcurrentOperationException;

    /**
     * @param networkName
     * @param displayText
     * @param physicalNetworkId
     * @param broadcastUri      TODO set the guru name based on the broadcastUri?
     * @param startIp
     * @param endIP             TODO
     * @param gateway
     * @param netmask
     * @param networkOwnerId
     * @param vpcId             TODO
     * @param sourceNat
     * @return
     * @throws InsufficientCapacityException
     * @throws ConcurrentOperationException
     * @throws ResourceAllocationException
     */
    Network createPrivateNetwork(String networkName, String displayText, long physicalNetworkId, String broadcastUri, String startIp, String endIP, String gateway,
                                 String netmask, long networkOwnerId, Long vpcId, Boolean sourceNat, Long networkOfferingId) throws ResourceAllocationException,
            ConcurrentOperationException,
            InsufficientCapacityException;

    /* Requests an IP address for the guest nic */
    NicSecondaryIp allocateSecondaryGuestIP(long nicId, String ipaddress) throws InsufficientAddressCapacityException;

    boolean releaseSecondaryIpFromNic(long ipAddressId);

    /* lists the nic informaton */
    List<? extends Nic> listNics(ListNicsCmd listNicsCmd);

    Map<Network.Capability, String> getNetworkOfferingServiceCapabilities(NetworkOffering offering, Service service);

    IpAddress updateIP(Long id, String customId, Boolean displayIp);

    boolean configureNicSecondaryIp(NicSecondaryIp secIp);
}
