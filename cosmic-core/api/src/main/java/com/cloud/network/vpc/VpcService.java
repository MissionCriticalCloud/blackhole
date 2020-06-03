package com.cloud.network.vpc;

import com.cloud.api.command.user.vpc.ListPrivateGatewaysCmd;
import com.cloud.api.command.user.vpc.ListStaticRoutesCmd;
import com.cloud.legacymodel.exceptions.ConcurrentOperationException;
import com.cloud.legacymodel.exceptions.InsufficientAddressCapacityException;
import com.cloud.legacymodel.exceptions.InsufficientCapacityException;
import com.cloud.legacymodel.exceptions.NetworkRuleConflictException;
import com.cloud.legacymodel.exceptions.ResourceAllocationException;
import com.cloud.legacymodel.exceptions.ResourceUnavailableException;
import com.cloud.model.enumeration.AdvertMethod;
import com.cloud.legacymodel.network.vpc.PrivateGateway;
import com.cloud.legacymodel.network.vpc.StaticRoute;
import com.cloud.legacymodel.network.vpc.Vpc;
import com.cloud.legacymodel.utils.Pair;
import com.cloud.model.enumeration.ComplianceStatus;
import com.cloud.network.IpAddress;

import java.util.List;
import java.util.Map;

public interface VpcService {

    /**
     * Persists VPC record in the database
     *
     * @param zoneId
     * @param vpcOffId
     * @param vpcOwnerId
     * @param vpcName
     * @param displayText
     * @param cidr
     * @param networkDomain TODO
     * @param displayVpc    TODO
     * @return
     * @throws ResourceAllocationException TODO
     */
    public Vpc createVpc(long zoneId, long vpcOffId, long vpcOwnerId, String vpcName, String displayText, String cidr, String networkDomain, Boolean displayVpc,
                         String sourceNatList, String syslogServerList, Long advertInterval, AdvertMethod advertMethod)
            throws ResourceAllocationException;

    /**
     * Deletes a VPC
     *
     * @param vpcId
     * @return
     * @throws InsufficientCapacityException
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    public boolean deleteVpc(long vpcId) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Updates VPC with new name/displayText
     *
     * @param vpcId
     * @param vpcName
     * @param displayText
     * @param customId    TODO
     * @param displayVpc  TODO
     * @return
     */
    public Vpc updateVpc(long vpcId, String vpcName, String displayText, String customId, Boolean displayVpc, Long vpcOfferingId, String sourceNatList, String syslogServerList,
                         Long advertInterval, AdvertMethod advertMethod, ComplianceStatus complianceStatus);

    /**
     * Lists VPC(s) based on the parameters passed to the method call
     *
     * @param id
     * @param vpcName
     * @param displayText
     * @param supportedServicesStr
     * @param cidr
     * @param state                TODO
     * @param accountName
     * @param domainId
     * @param keyword
     * @param startIndex
     * @param pageSizeVal
     * @param zoneId               TODO
     * @param isRecursive          TODO
     * @param listAll              TODO
     * @param restartRequired      TODO
     * @param tags                 TODO
     * @param projectId            TODO
     * @param display              TODO
     * @param vpc
     * @return
     */
    public Pair<List<? extends Vpc>, Integer> listVpcs(Long id, String vpcName, String displayText, List<String> supportedServicesStr, String cidr, Long vpcOffId, String state,
                                                       String accountName, Long domainId, String keyword, Long startIndex, Long pageSizeVal, Long zoneId, Boolean isRecursive,
                                                       Boolean listAll, Boolean restartRequired,
                                                       Map<String, String> tags, Long projectId, Boolean display, String complianceStatus);

    /**
     * Starts VPC which includes starting VPC provider and applying all the neworking rules on the backend
     *
     * @param vpcId
     * @param destroyOnFailure TODO
     * @return
     * @throws InsufficientCapacityException
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    boolean startVpc(long vpcId, boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Shuts down the VPC which includes shutting down all VPC provider and rules cleanup on the backend
     *
     * @param vpcId
     * @return
     * @throws ConcurrentOperationException
     * @throws ResourceUnavailableException
     */
    boolean shutdownVpc(long vpcId) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Restarts the VPC. VPC gets shutdown and started as a part of it
     *
     * @param id
     * @param cleanUp
     * @return
     * @throws InsufficientCapacityException
     */
    boolean restartVpc(long id, boolean cleanUp) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Returns a Private gateway found in the VPC by id
     *
     * @param id
     * @return
     */
    PrivateGateway getVpcPrivateGateway(long id);

    /**
     * Persists VPC private gateway in the Database.
     *
     * @param vpcId
     * @param ipAddress
     * @param gateway
     * @param netmask
     * @param gatewayDomainId
     * @param networkId
     * @param aclId
     * @return
     * @throws InsufficientCapacityException
     * @throws ConcurrentOperationException
     * @throws ResourceAllocationException
     */
    public PrivateGateway createVpcPrivateGateway(long vpcId, String ipAddress, String gateway, String netmask, long gatewayDomainId,
                                                  Long networkId, Boolean isSourceNat, Long aclId) throws ResourceAllocationException, ConcurrentOperationException,
            InsufficientCapacityException;

    /**
     * Applies VPC private gateway on the backend, so it becomes functional
     *
     * @param gatewayId
     * @param destroyOnFailure TODO
     * @return
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    public PrivateGateway applyVpcPrivateGateway(long gatewayId, boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Deletes VPC private gateway
     *
     * @param id
     * @return
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    boolean deleteVpcPrivateGateway(long gatewayId) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Returns the list of Private gateways existing in the VPC
     *
     * @param listPrivateGatewaysCmd
     * @return
     */
    public Pair<List<PrivateGateway>, Integer> listPrivateGateway(ListPrivateGatewaysCmd listPrivateGatewaysCmd);

    /**
     * Returns Static Route found by Id
     *
     * @param routeId
     * @return
     */
    StaticRoute getStaticRoute(long routeId);

    /**
     * Applies existing Static Routes to the VPC elements
     *
     * @param vpcId
     * @return
     * @throws ResourceUnavailableException
     */
    public boolean applyStaticRoutesForVpc(long vpcId) throws ResourceUnavailableException;

    /**
     * Deletes static route from the backend and the database
     *
     * @param routeId
     * @return TODO
     * @throws ResourceUnavailableException
     */
    public boolean revokeStaticRoute(long routeId) throws ResourceUnavailableException;

    /**
     * Persists static route entry in the Database
     *
     * @param gatewayId
     * @param cidr
     * @return
     */
    public StaticRoute createStaticRoute(long vpcId, String cidr, String gwIpAddress) throws NetworkRuleConflictException;

    /**
     * Lists static routes based on parameters passed to the call
     *
     * @param listStaticRoutesCmd
     * @return
     */
    public Pair<List<? extends StaticRoute>, Integer> listStaticRoutes(ListStaticRoutesCmd cmd);

    /**
     * Associates IP address from the Public network, to the VPC
     *
     * @param ipId
     * @param vpcId
     * @return
     * @throws ResourceAllocationException
     * @throws ResourceUnavailableException
     * @throws InsufficientAddressCapacityException
     * @throws ConcurrentOperationException
     */
    IpAddress associateIPToVpc(long ipId, long vpcId) throws ResourceAllocationException, ResourceUnavailableException, InsufficientAddressCapacityException,
            ConcurrentOperationException;

    /**
     * @param routeId
     * @return
     */
    public boolean applyStaticRoute(long routeId) throws ResourceUnavailableException;
}
