package com.cloud.network;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupExternalFirewallCommand;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.RemoteAccessVpnCfgCommand;
import com.cloud.agent.api.routing.SetFirewallRulesCommand;
import com.cloud.agent.api.routing.SetPortForwardingRulesCommand;
import com.cloud.agent.api.routing.SetStaticNatRulesCommand;
import com.cloud.agent.api.routing.VpnUsersCfgCommand;
import com.cloud.agent.api.to.FirewallRuleTO;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.PortForwardingRuleTO;
import com.cloud.agent.api.to.StaticNatRuleTO;
import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientNetworkCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.ExternalFirewallDeviceDao;
import com.cloud.network.dao.ExternalFirewallDeviceVO;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.InlineLoadBalancerNicMapDao;
import com.cloud.network.dao.InlineLoadBalancerNicMapVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkExternalFirewallDao;
import com.cloud.network.dao.NetworkExternalFirewallVO;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.VpnUserDao;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.resource.ResourceStateAdapter;
import com.cloud.resource.ServerResource;
import com.cloud.resource.UnableDeleteHostException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.net.UrlUtil;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.ExternalFirewallResponse;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.ExternalNetworkDeviceManager.NetworkDevice;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ExternalFirewallDeviceManagerImpl extends AdapterBase implements ExternalFirewallDeviceManager, ResourceStateAdapter {

    private static final Logger s_logger = LoggerFactory.getLogger(ExternalFirewallDeviceManagerImpl.class);
    @Inject
    HostDao _hostDao;
    @Inject
    NetworkServiceMapDao _ntwkSrvcProviderDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    NetworkModel _networkModel;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    InlineLoadBalancerNicMapDao _inlineLoadBalancerNicMapDao;
    @Inject
    NicDao _nicDao;
    @Inject
    AgentManager _agentMgr;
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    AccountDao _accountDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkServiceProviderDao _physicalNetworkServiceProviderDao;
    @Inject
    AccountManager _accountMgr;
    @Inject
    UserStatisticsDao _userStatsDao;
    @Inject
    NetworkDao _networkDao;
    @Inject
    DomainRouterDao _routerDao;
    @Inject
    LoadBalancerDao _loadBalancerDao;
    @Inject
    PortForwardingRulesDao _portForwardingRulesDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    ExternalFirewallDeviceDao _externalFirewallDeviceDao;
    @Inject
    NetworkExternalFirewallDao _networkExternalFirewallDao;
    @Inject
    VpnUserDao _vpnUsersDao;
    @Inject
    HostDetailsDao _hostDetailDao;
    @Inject
    FirewallRulesDao _fwRulesDao;
    private long _defaultFwCapacity;

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        _resourceMgr.registerResourceStateAdapter(this.getClass().getSimpleName(), this);
        _defaultFwCapacity = NumbersUtil.parseLong(_configDao.getValue(Config.DefaultExternalFirewallCapacity.key()), 50);
        return true;
    }

    @Override
    @DB
    public ExternalFirewallDeviceVO addExternalFirewall(final long physicalNetworkId, final String url, final String username, final String password, final String deviceName,
                                                        final ServerResource resource) {
        final String guid;
        PhysicalNetworkVO pNetwork;
        final NetworkDevice ntwkDevice = NetworkDevice.getNetworkDevice(deviceName);
        final long zoneId;

        if ((ntwkDevice == null) || (url == null) || (username == null) || (resource == null) || (password == null)) {
            throw new InvalidParameterValueException("Atleast one of the required parameters (url, username, password,"
                    + " server resource, zone id/physical network id) is not specified or a valid parameter.");
        }

        pNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNetwork == null) {
            throw new InvalidParameterValueException("Could not find phyical network with ID: " + physicalNetworkId);
        }
        zoneId = pNetwork.getDataCenterId();

        final PhysicalNetworkServiceProviderVO ntwkSvcProvider =
                _physicalNetworkServiceProviderDao.findByServiceProvider(pNetwork.getId(), ntwkDevice.getNetworkServiceProvder());
        if (ntwkSvcProvider == null) {
            throw new CloudRuntimeException("Network Service Provider: " + ntwkDevice.getNetworkServiceProvder() + " is not enabled in the physical network: " +
                    physicalNetworkId + "to add this device");
        } else if (ntwkSvcProvider.getState() == PhysicalNetworkServiceProvider.State.Shutdown) {
            throw new CloudRuntimeException("Network Service Provider: " + ntwkSvcProvider.getProviderName() +
                    " is not added or in shutdown state in the physical network: " + physicalNetworkId + "to add this device");
        }

        final URI uri;
        try {
            uri = new URI(url);
        } catch (final Exception e) {
            s_logger.debug(e.toString());
            throw new InvalidParameterValueException(e.getMessage());
        }

        final String ipAddress = uri.getHost();
        final Map hostDetails = new HashMap<String, String>();
        guid = getExternalNetworkResourceGuid(pNetwork.getId(), deviceName, ipAddress);
        hostDetails.put("name", guid);
        hostDetails.put("guid", guid);
        hostDetails.put("zoneId", String.valueOf(pNetwork.getDataCenterId()));
        hostDetails.put("ip", ipAddress);
        hostDetails.put("physicalNetworkId", String.valueOf(pNetwork.getId()));
        hostDetails.put("username", username);
        hostDetails.put("password", password);
        hostDetails.put("deviceName", deviceName);
        final Map<String, String> configParams = new HashMap<>();
        UrlUtil.parseQueryParameters(uri.getQuery(), false, configParams);
        hostDetails.putAll(configParams);

        // let the server resource to do parameters validation
        try {
            resource.configure(guid, hostDetails);
        } catch (final ConfigurationException e) {
            throw new CloudRuntimeException(e.getMessage());
        }

        final Host externalFirewall = _resourceMgr.addHost(zoneId, resource, Host.Type.ExternalFirewall, hostDetails);
        if (externalFirewall != null) {
            final PhysicalNetworkVO pNetworkFinal = pNetwork;
            return Transaction.execute(new TransactionCallback<ExternalFirewallDeviceVO>() {
                @Override
                public ExternalFirewallDeviceVO doInTransaction(final TransactionStatus status) {
                    final boolean dedicatedUse =
                            (configParams.get(ApiConstants.FIREWALL_DEVICE_DEDICATED) != null) ? Boolean.parseBoolean(configParams.get(ApiConstants.FIREWALL_DEVICE_DEDICATED))
                                    : false;
                    long capacity = NumbersUtil.parseLong(configParams.get(ApiConstants.FIREWALL_DEVICE_CAPACITY), 0);
                    if (capacity == 0) {
                        capacity = _defaultFwCapacity;
                    }

                    final ExternalFirewallDeviceVO fwDevice =
                            new ExternalFirewallDeviceVO(externalFirewall.getId(), pNetworkFinal.getId(), ntwkSvcProvider.getProviderName(), deviceName, capacity,
                                    dedicatedUse);

                    _externalFirewallDeviceDao.persist(fwDevice);

                    final DetailVO hostDetail = new DetailVO(externalFirewall.getId(), ApiConstants.FIREWALL_DEVICE_ID, String.valueOf(fwDevice.getId()));
                    _hostDetailDao.persist(hostDetail);

                    return fwDevice;
                }
            });
        } else {
            return null;
        }
    }

    @Override
    public boolean deleteExternalFirewall(final Long hostId) {
        final HostVO externalFirewall = _hostDao.findById(hostId);
        if (externalFirewall == null) {
            throw new InvalidParameterValueException("Could not find an external firewall with ID: " + hostId);
        }

        final DetailVO fwHostDetails = _hostDetailDao.findDetail(hostId, ApiConstants.FIREWALL_DEVICE_ID);
        final long fwDeviceId = Long.parseLong(fwHostDetails.getValue());

        // check if any networks are using this balancer device
        final List<NetworkExternalFirewallVO> networks = _networkExternalFirewallDao.listByFirewallDeviceId(fwDeviceId);
        if ((networks != null) && !networks.isEmpty()) {
            throw new CloudRuntimeException("Delete can not be done as there are networks using the firewall device ");
        }

        try {
            // put the host in maintenance state in order for it to be deleted
            externalFirewall.setResourceState(ResourceState.Maintenance);
            _hostDao.update(hostId, externalFirewall);
            _resourceMgr.deleteHost(hostId, false, false);

            // delete the external load balancer entry
            _externalFirewallDeviceDao.remove(fwDeviceId);
            return true;
        } catch (final Exception e) {
            s_logger.debug("Failed to delete external firewall device due to " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<Host> listExternalFirewalls(final long physicalNetworkId, final String deviceName) {
        final List<Host> firewallHosts = new ArrayList<>();
        final NetworkDevice fwNetworkDevice = NetworkDevice.getNetworkDevice(deviceName);
        PhysicalNetworkVO pNetwork;

        pNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNetwork == null) {
            throw new InvalidParameterValueException("Could not find phyical network with ID: " + physicalNetworkId);
        }

        if ((pNetwork == null) || (fwNetworkDevice == null)) {
            throw new InvalidParameterValueException("Atleast one of ther required parameter physical networkId, device name is missing or invalid.");
        }

        final PhysicalNetworkServiceProviderVO ntwkSvcProvider =
                _physicalNetworkServiceProviderDao.findByServiceProvider(pNetwork.getId(), fwNetworkDevice.getNetworkServiceProvder());
        if (ntwkSvcProvider == null) {
            return null;
        }

        final List<ExternalFirewallDeviceVO> fwDevices = _externalFirewallDeviceDao.listByPhysicalNetworkAndProvider(physicalNetworkId, ntwkSvcProvider.getProviderName());
        for (final ExternalFirewallDeviceVO fwDevice : fwDevices) {
            firewallHosts.add(_hostDao.findById(fwDevice.getHostId()));
        }
        return firewallHosts;
    }

    @Override
    public ExternalFirewallDeviceVO findSuitableFirewallForNetwork(final Network network) throws InsufficientCapacityException {
        final long physicalNetworkId = network.getPhysicalNetworkId();
        final List<ExternalFirewallDeviceVO> fwDevices = _externalFirewallDeviceDao.listByPhysicalNetwork(physicalNetworkId);

        // loop through the firewall device in the physical network and pick the first-fit
        for (final ExternalFirewallDeviceVO fwDevice : fwDevices) {
            // max number of guest networks that can be mapped to this device
            long fullCapacity = fwDevice.getCapacity();
            if (fullCapacity == 0) {
                fullCapacity = _defaultFwCapacity; // if capacity not configured then use the default
            }

            // get the list of guest networks that are mapped to this load balancer
            final List<NetworkExternalFirewallVO> mappedNetworks = _networkExternalFirewallDao.listByFirewallDeviceId(fwDevice.getId());

            final long usedCapacity = (mappedNetworks == null) ? 0 : mappedNetworks.size();
            if ((fullCapacity - usedCapacity) > 0) {
                return fwDevice;
            }
        }
        throw new InsufficientNetworkCapacityException("Unable to find a firewall provider with sufficient capcity " + " to implement the network", DataCenter.class,
                network.getDataCenterId());
    }

    @Override
    public ExternalFirewallDeviceVO getExternalFirewallForNetwork(final Network network) {
        final NetworkExternalFirewallVO fwDeviceForNetwork = _networkExternalFirewallDao.findByNetworkId(network.getId());
        if (fwDeviceForNetwork != null) {
            final long fwDeviceId = fwDeviceForNetwork.getExternalFirewallDeviceId();
            final ExternalFirewallDeviceVO fwDevice = _externalFirewallDeviceDao.findById(fwDeviceId);
            assert (fwDevice != null);
            return fwDevice;
        }
        return null;
    }

    @Override
    public boolean applyFirewallRules(final Network network, final List<? extends FirewallRule> rules) throws ResourceUnavailableException {
        // Find the external firewall in this zone
        final long zoneId = network.getDataCenterId();
        final DataCenterVO zone = _dcDao.findById(zoneId);
        final ExternalFirewallDeviceVO fwDeviceVO = getExternalFirewallForNetwork(network);
        // During destroy, device reference may already been clean up, then we just return true
        if (fwDeviceVO == null) {
            return true;
        }
        final HostVO externalFirewall = _hostDao.findById(fwDeviceVO.getHostId());

        assert (externalFirewall != null);

        if (network.getState() == Network.State.Allocated) {
            s_logger.debug("External firewall was asked to apply firewall rules for network with ID " + network.getId() +
                    "; this network is not implemented. Skipping backend commands.");
            return true;
        }

        final List<FirewallRuleTO> rulesTO = new ArrayList<>();
        final NetworkVO networkVO = _networkDao.findById(network.getId());
        final NetworkOfferingVO offering = _networkOfferingDao.findById(networkVO.getNetworkOfferingId());
        final Boolean defaultEgressPolicy = offering.getEgressDefaultPolicy();

        for (final FirewallRule rule : rules) {
            if (rule.getSourceCidrList() == null && (rule.getPurpose() == Purpose.Firewall || rule.getPurpose() == Purpose.NetworkACL)) {
                _fwRulesDao.loadSourceCidrs((FirewallRuleVO) rule);
            }
            final FirewallRuleTO ruleTO;
            if (rule.getPurpose() == Purpose.Firewall && rule.getTrafficType() == FirewallRule.TrafficType.Egress) {
                final String guestVlanTag = BroadcastDomainType.getValue(network.getBroadcastUri());
                final String guestCidr = network.getCidr();
                ruleTO = new FirewallRuleTO(rule, guestVlanTag, rule.getTrafficType(), guestCidr, defaultEgressPolicy, rule.getType());
            } else {
                final IpAddress sourceIp = _networkModel.getIp(rule.getSourceIpAddressId());
                final Vlan vlan = _vlanDao.findById(sourceIp.getVlanId());

                ruleTO = new FirewallRuleTO(rule, vlan.getVlanTag(), sourceIp.getAddress().addr());
            }
            rulesTO.add(ruleTO);
        }

        //Firewall rules configured for staticNAT/PF
        sendFirewallRules(rulesTO, zone, externalFirewall.getId());

        return true;
    }

    @Override
    public boolean manageGuestNetworkWithExternalFirewall(final boolean add, final Network network) throws ResourceUnavailableException, InsufficientCapacityException {
        if (network.getTrafficType() != TrafficType.Guest) {
            s_logger.trace("External firewall can only be used for add/remove guest networks.");
            return false;
        }

        final long zoneId = network.getDataCenterId();
        final DataCenterVO zone = _dcDao.findById(zoneId);
        HostVO externalFirewall = null;

        if (add) {
            final GlobalLock deviceMapLock = GlobalLock.getInternLock("NetworkFirewallDeviceMap");
            try {
                if (deviceMapLock.lock(120)) {
                    try {
                        final ExternalFirewallDeviceVO device = findSuitableFirewallForNetwork(network);
                        final long externalFirewallId = device.getId();

                        final NetworkExternalFirewallVO networkFW = new NetworkExternalFirewallVO(network.getId(), externalFirewallId);
                        _networkExternalFirewallDao.persist(networkFW);

                        externalFirewall = _hostDao.findById(device.getHostId());
                    } finally {
                        deviceMapLock.unlock();
                    }
                }
            } finally {
                deviceMapLock.releaseRef();
            }
        } else {
            final ExternalFirewallDeviceVO fwDeviceVO = getExternalFirewallForNetwork(network);
            if (fwDeviceVO == null) {
                s_logger.warn("Network shutdown requested on external firewall element, which did not implement the network."
                        + " Either network implement failed half way through or already network shutdown is completed.");
                return true;
            }
            externalFirewall = _hostDao.findById(fwDeviceVO.getHostId());
        }

        final Account account = _accountDao.findByIdIncludingRemoved(network.getAccountId());

        final NetworkOffering offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        final boolean sharedSourceNat = offering.getSharedSourceNat();

        IPAddressVO sourceNatIp = null;
        if (!sharedSourceNat) {
            // Get the source NAT IP address for this network
            final List<? extends IpAddress> sourceNatIps = _networkModel.listPublicIpsAssignedToAccount(network.getAccountId(), zoneId, true);

            for (final IpAddress ipAddress : sourceNatIps) {
                if (ipAddress.getAssociatedWithNetworkId().longValue() == network.getId()) {
                    sourceNatIp = _ipAddressDao.findById(ipAddress.getId());
                    break;
                }
            }
            if (sourceNatIp == null) {
                final String errorMsg = "External firewall was unable to find the source NAT IP address for network " + network.getName();
                s_logger.error(errorMsg);
                return true;
            }
        }

        // Send a command to the external firewall to implement or shutdown the guest network
        final long guestVlanTag = Long.parseLong(BroadcastDomainType.getValue(network.getBroadcastUri()));
        final String guestVlanGateway = network.getGateway();
        final String guestVlanCidr = network.getCidr();
        String sourceNatIpAddress = null;
        String publicVlanTag = null;

        if (sourceNatIp != null) {
            sourceNatIpAddress = sourceNatIp.getAddress().addr();
            final VlanVO publicVlan = _vlanDao.findById(sourceNatIp.getVlanId());
            publicVlanTag = publicVlan.getVlanTag();
        }

        // Get network rate
        final Integer networkRate = _networkModel.getNetworkRate(network.getId(), null);

        final IpAddressTO ip = new IpAddressTO(account.getAccountId(), sourceNatIpAddress, add, false, !sharedSourceNat, publicVlanTag, null, null, null, networkRate, false);
        final IpAddressTO[] ips = new IpAddressTO[1];
        ips[0] = ip;
        final IpAssocCommand cmd = new IpAssocCommand(ips);
        cmd.setAccessDetail(NetworkElementCommand.GUEST_NETWORK_GATEWAY, guestVlanGateway);
        cmd.setAccessDetail(NetworkElementCommand.GUEST_NETWORK_CIDR, guestVlanCidr);
        cmd.setAccessDetail(NetworkElementCommand.GUEST_VLAN_TAG, String.valueOf(guestVlanTag));
        final Answer answer = _agentMgr.easySend(externalFirewall.getId(), cmd);

        final List<String> reservedIpAddressesForGuestNetwork = _nicDao.listIpAddressInNetwork(network.getId());

        if (answer == null || !answer.getResult()) {
            final String action = add ? "implement" : "shutdown";
            final String answerDetails = (answer != null) ? answer.getDetails() : "answer was null";
            final String msg =
                    "External firewall was unable to " + action + " the guest network on the external firewall in zone " + zone.getName() + " due to " + answerDetails;
            s_logger.error(msg);
            if (!add && (!reservedIpAddressesForGuestNetwork.contains(network.getGateway()))) {
                // If we failed the implementation as well, then just return, no complain
                s_logger.error("Skip the shutdown of guest network on SRX because it seems we didn't implement it as well");
                return true;
            }
            throw new ResourceUnavailableException(msg, DataCenter.class, zoneId);
        }

        if (add && (!reservedIpAddressesForGuestNetwork.contains(network.getGateway()))) {
            // Insert a new NIC for this guest network to reserve the gateway address
            _networkMgr.savePlaceholderNic(network, network.getGateway(), null, null);
        }

        // Delete any mappings used for inline external load balancers in this network
        final List<NicVO> nicsInNetwork = _nicDao.listByNetworkId(network.getId());
        for (final NicVO nic : nicsInNetwork) {
            final InlineLoadBalancerNicMapVO mapping = _inlineLoadBalancerNicMapDao.findByNicId(nic.getId());

            if (mapping != null) {
                _nicDao.expunge(mapping.getNicId());
                _inlineLoadBalancerNicMapDao.expunge(mapping.getId());
            }
        }

        // on network shutdown, delete placeHolder nics used for the firewall device
        if (!add) {
            final List<NicVO> nics = _nicDao.listByNetworkId(network.getId());
            for (final NicVO nic : nics) {
                if (nic.getVmType() == null && nic.getReservationStrategy().equals(ReservationStrategy.PlaceHolder) && nic.getIPv4Address().equals(network.getGateway())) {
                    s_logger.debug("Removing placeholder nic " + nic + " for the network " + network);
                    _nicDao.remove(nic.getId());
                }
            }
            freeFirewallForNetwork(network);
        }

        final String action = add ? "implemented" : "shut down";
        s_logger.debug("External firewall has " + action + " the guest network for account " + account.getAccountName() + "(id = " + account.getAccountId() +
                ") with VLAN tag " + guestVlanTag);

        return true;
    }

    @DB
    protected boolean freeFirewallForNetwork(final Network network) {
        final GlobalLock deviceMapLock = GlobalLock.getInternLock("NetworkFirewallDeviceMap");
        try {
            if (deviceMapLock.lock(120)) {
                try {
                    final NetworkExternalFirewallVO fwDeviceForNetwork = _networkExternalFirewallDao.findByNetworkId(network.getId());
                    if (fwDeviceForNetwork != null) {
                        _networkExternalFirewallDao.remove(fwDeviceForNetwork.getId());
                    }
                } catch (final Exception exception) {
                    s_logger.error("Failed to release firewall device for the network" + network.getId() + " due to " + exception.getMessage());
                    return false;
                } finally {
                    deviceMapLock.unlock();
                }
            }
        } finally {
            deviceMapLock.releaseRef();
        }
        return true;
    }

    @Override
    public boolean applyPortForwardingRules(final Network network, final List<? extends PortForwardingRule> rules) throws ResourceUnavailableException {
        // Find the external firewall in this zone
        final long zoneId = network.getDataCenterId();
        final DataCenterVO zone = _dcDao.findById(zoneId);
        final ExternalFirewallDeviceVO fwDeviceVO = getExternalFirewallForNetwork(network);
        final HostVO externalFirewall = _hostDao.findById(fwDeviceVO.getHostId());

        assert (externalFirewall != null);

        if (network.getState() == Network.State.Allocated) {
            s_logger.debug("External firewall was asked to apply firewall rules for network with ID " + network.getId() +
                    "; this network is not implemented. Skipping backend commands.");
            return true;
        }

        final List<PortForwardingRuleTO> pfRules = new ArrayList<>();

        for (final PortForwardingRule rule : rules) {
            final IpAddress sourceIp = _networkModel.getIp(rule.getSourceIpAddressId());
            final Vlan vlan = _vlanDao.findById(sourceIp.getVlanId());

            final PortForwardingRuleTO ruleTO = new PortForwardingRuleTO(rule, vlan.getVlanTag(), sourceIp.getAddress().addr());
            pfRules.add(ruleTO);
        }

        sendPortForwardingRules(pfRules, zone, externalFirewall.getId());
        return true;
    }

    protected void sendPortForwardingRules(final List<PortForwardingRuleTO> portForwardingRules, final DataCenter zone, final long externalFirewallId) throws
            ResourceUnavailableException {
        if (!portForwardingRules.isEmpty()) {
            final SetPortForwardingRulesCommand cmd = new SetPortForwardingRulesCommand(portForwardingRules);
            final Answer answer = _agentMgr.easySend(externalFirewallId, cmd);
            if (answer == null || !answer.getResult()) {
                final String details = (answer != null) ? answer.getDetails() : "details unavailable";
                final String msg = "External firewall was unable to apply port forwarding rules to the SRX appliance in zone " + zone.getName() + " due to: " + details + ".";
                s_logger.error(msg);
                throw new ResourceUnavailableException(msg, DataCenter.class, zone.getId());
            }
        }
    }

    protected void sendFirewallRules(final List<FirewallRuleTO> firewallRules, final DataCenter zone, final long externalFirewallId) throws ResourceUnavailableException {
        if (!firewallRules.isEmpty()) {
            final SetFirewallRulesCommand cmd = new SetFirewallRulesCommand(firewallRules);
            final Answer answer = _agentMgr.easySend(externalFirewallId, cmd);
            if (answer == null || !answer.getResult()) {
                final String details = (answer != null) ? answer.getDetails() : "details unavailable";
                final String msg = "External firewall was unable to apply static nat rules to the SRX appliance in zone " + zone.getName() + " due to: " + details + ".";
                s_logger.error(msg);
                throw new ResourceUnavailableException(msg, DataCenter.class, zone.getId());
            }
        }
    }

    public String getExternalNetworkResourceGuid(final long physicalNetworkId, final String deviceName, final String ip) {
        return physicalNetworkId + "-" + deviceName + "-" + ip;
    }

    public void setExternalFirewallForNetwork(final Network network, final long externalFWDeviceID) {
        final NetworkExternalFirewallVO fwDeviceForNetwork = new NetworkExternalFirewallVO(network.getId(), externalFWDeviceID);
        _networkExternalFirewallDao.persist(fwDeviceForNetwork);
    }

    public ExternalFirewallResponse createExternalFirewallResponse(final Host externalFirewall) {
        final Map<String, String> fwDetails = _hostDetailDao.findDetails(externalFirewall.getId());
        final ExternalFirewallResponse response = new ExternalFirewallResponse();
        response.setId(externalFirewall.getUuid());
        response.setIpAddress(externalFirewall.getPrivateIpAddress());
        response.setUsername(fwDetails.get("username"));
        response.setPublicInterface(fwDetails.get("publicInterface"));
        response.setUsageInterface(fwDetails.get("usageInterface"));
        response.setPrivateInterface(fwDetails.get("privateInterface"));
        response.setPublicZone(fwDetails.get("publicZone"));
        response.setPrivateZone(fwDetails.get("privateZone"));
        response.setNumRetries(fwDetails.get("numRetries"));
        response.setTimeout(fwDetails.get("timeout"));
        return response;
    }

    public boolean applyStaticNatRules(final Network network, final List<? extends StaticNat> rules) throws ResourceUnavailableException {
        final long zoneId = network.getDataCenterId();
        final DataCenterVO zone = _dcDao.findById(zoneId);
        final ExternalFirewallDeviceVO fwDeviceVO = getExternalFirewallForNetwork(network);
        final HostVO externalFirewall = _hostDao.findById(fwDeviceVO.getHostId());

        assert (externalFirewall != null);

        if (network.getState() == Network.State.Allocated) {
            s_logger.debug("External firewall was asked to apply firewall rules for network with ID " + network.getId() +
                    "; this network is not implemented. Skipping backend commands.");
            return true;
        }

        final List<StaticNatRuleTO> staticNatRules = new ArrayList<>();

        for (final StaticNat rule : rules) {
            final IpAddress sourceIp = _networkModel.getIp(rule.getSourceIpAddressId());
            final Vlan vlan = _vlanDao.findById(sourceIp.getVlanId());

            final StaticNatRuleTO ruleTO =
                    new StaticNatRuleTO(0, vlan.getVlanTag(), sourceIp.getAddress().addr(), -1, -1, rule.getDestIpAddress(), -1, -1, "any", rule.isForRevoke(), false);
            staticNatRules.add(ruleTO);
        }

        sendStaticNatRules(staticNatRules, zone, externalFirewall.getId());

        return true;
    }

    protected void sendStaticNatRules(final List<StaticNatRuleTO> staticNatRules, final DataCenter zone, final long externalFirewallId) throws ResourceUnavailableException {
        if (!staticNatRules.isEmpty()) {
            final SetStaticNatRulesCommand cmd = new SetStaticNatRulesCommand(staticNatRules, null);
            final Answer answer = _agentMgr.easySend(externalFirewallId, cmd);
            if (answer == null || !answer.getResult()) {
                final String details = (answer != null) ? answer.getDetails() : "details unavailable";
                final String msg = "External firewall was unable to apply static nat rules to the SRX appliance in zone " + zone.getName() + " due to: " + details + ".";
                s_logger.error(msg);
                throw new ResourceUnavailableException(msg, DataCenter.class, zone.getId());
            }
        }
    }

    public boolean applyIps(final Network network, final List<? extends PublicIpAddress> ipAddresses) throws ResourceUnavailableException {
        return true;
    }

    public boolean manageRemoteAccessVpn(final boolean create, final Network network, final RemoteAccessVpn vpn) throws ResourceUnavailableException {
        final ExternalFirewallDeviceVO fwDeviceVO = getExternalFirewallForNetwork(network);
        final HostVO externalFirewall = _hostDao.findById(fwDeviceVO.getHostId());

        if (externalFirewall == null) {
            return false;
        }

        // Create/delete VPN
        final IpAddress ip = _networkModel.getIp(vpn.getServerAddressId());

        // Mask the IP range with the network's VLAN tag
        final String[] ipRange = vpn.getIpRange().split("-");
        final DataCenterVO zone = _dcDao.findById(network.getDataCenterId());
        final int vlanTag = Integer.parseInt(BroadcastDomainType.getValue(network.getBroadcastUri()));
        final int offset = getVlanOffset(network.getPhysicalNetworkId(), vlanTag);
        final int cidrSize = getGloballyConfiguredCidrSize();

        for (int i = 0; i < 2; i++) {
            ipRange[i] = NetUtils.long2Ip((NetUtils.ip2Long(ipRange[i]) & 0xff000000) | (offset << (32 - cidrSize)));
        }

        final String maskedIpRange = ipRange[0] + "-" + ipRange[1];

        final RemoteAccessVpnCfgCommand createVpnCmd =
                new RemoteAccessVpnCfgCommand(create, ip.getAddress().addr(), vpn.getLocalIp(), maskedIpRange, vpn.getIpsecPresharedKey(), false);
        createVpnCmd.setAccessDetail(NetworkElementCommand.ACCOUNT_ID, String.valueOf(network.getAccountId()));
        createVpnCmd.setAccessDetail(NetworkElementCommand.GUEST_NETWORK_CIDR, network.getCidr());
        final Answer answer = _agentMgr.easySend(externalFirewall.getId(), createVpnCmd);
        if (answer == null || !answer.getResult()) {
            final String details = (answer != null) ? answer.getDetails() : "details unavailable";
            final String msg = "External firewall was unable to create a remote access VPN in zone " + zone.getName() + " due to: " + details + ".";
            s_logger.error(msg);
            throw new ResourceUnavailableException(msg, DataCenter.class, zone.getId());
        }

        // Add/delete users
        final List<VpnUserVO> vpnUsers = _vpnUsersDao.listByAccount(vpn.getAccountId());
        return manageRemoteAccessVpnUsers(network, vpn, vpnUsers);
    }

    public int getVlanOffset(final long physicalNetworkId, final int vlanTag) {
        final PhysicalNetworkVO pNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNetwork == null) {
            throw new CloudRuntimeException("Could not find the physical Network " + physicalNetworkId + ".");
        }

        if (pNetwork.getVnet() == null) {
            throw new CloudRuntimeException("Could not find vlan range for physical Network " + physicalNetworkId + ".");
        }
        Integer lowestVlanTag = null;
        final List<Pair<Integer, Integer>> vnetList = pNetwork.getVnet();
        //finding the vlanrange in which the vlanTag lies.
        for (final Pair<Integer, Integer> vnet : vnetList) {
            if (vlanTag >= vnet.first() && vlanTag <= vnet.second()) {
                lowestVlanTag = vnet.first();
            }
        }
        if (lowestVlanTag == null) {
            throw new InvalidParameterValueException("The vlan tag does not belong to any of the existing vlan ranges");
        }
        return vlanTag - lowestVlanTag;
    }

    public int getGloballyConfiguredCidrSize() {
        try {
            final String globalVlanBits = _configDao.getValue(Config.GuestVlanBits.key());
            return 8 + Integer.parseInt(globalVlanBits);
        } catch (final Exception e) {
            throw new CloudRuntimeException("Failed to read the globally configured VLAN bits size.");
        }
    }

    public boolean manageRemoteAccessVpnUsers(final Network network, final RemoteAccessVpn vpn, final List<? extends VpnUser> vpnUsers) throws ResourceUnavailableException {
        final ExternalFirewallDeviceVO fwDeviceVO = getExternalFirewallForNetwork(network);
        final HostVO externalFirewall = _hostDao.findById(fwDeviceVO.getHostId());

        if (externalFirewall == null) {
            return false;
        }

        final List<VpnUser> addUsers = new ArrayList<>();
        final List<VpnUser> removeUsers = new ArrayList<>();
        for (final VpnUser user : vpnUsers) {
            if (user.getState() == VpnUser.State.Add || user.getState() == VpnUser.State.Active) {
                addUsers.add(user);
            } else if (user.getState() == VpnUser.State.Revoke) {
                removeUsers.add(user);
            }
        }

        final VpnUsersCfgCommand addUsersCmd = new VpnUsersCfgCommand(addUsers, removeUsers);
        addUsersCmd.setAccessDetail(NetworkElementCommand.ACCOUNT_ID, String.valueOf(network.getAccountId()));
        addUsersCmd.setAccessDetail(NetworkElementCommand.GUEST_NETWORK_CIDR, network.getCidr());

        final Answer answer = _agentMgr.easySend(externalFirewall.getId(), addUsersCmd);
        if (answer == null || !answer.getResult()) {
            final String details = (answer != null) ? answer.getDetails() : "details unavailable";
            final DataCenterVO zone = _dcDao.findById(network.getDataCenterId());
            final String msg = "External firewall was unable to add remote access users in zone " + zone.getName() + " due to: " + details + ".";
            s_logger.error(msg);
            throw new ResourceUnavailableException(msg, DataCenter.class, zone.getId());
        }

        return true;
    }

    @Override
    public HostVO createHostVOForConnectedAgent(final HostVO host, final StartupCommand[] cmd) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public HostVO createHostVOForDirectConnectAgent(final HostVO host, final StartupCommand[] startup, final ServerResource resource, final Map<String, String> details, final
    List<String> hostTags) {
        if (!(startup[0] instanceof StartupExternalFirewallCommand)) {
            return null;
        }
        host.setType(Host.Type.ExternalFirewall);
        return host;
    }

    @Override
    public DeleteHostAnswer deleteHost(final HostVO host, final boolean isForced, final boolean isForceDeleteStorage) throws UnableDeleteHostException {
        if (host.getType() != com.cloud.host.Host.Type.ExternalFirewall) {
            return null;
        }
        return new DeleteHostAnswer(true);
    }
}
