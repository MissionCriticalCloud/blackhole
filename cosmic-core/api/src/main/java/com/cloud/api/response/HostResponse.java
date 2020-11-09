package com.cloud.api.response;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseResponse;
import com.cloud.api.EntityReference;
import com.cloud.legacymodel.dc.Host;
import com.cloud.legacymodel.dc.HostStatus;
import com.cloud.model.enumeration.HostType;
import com.cloud.model.enumeration.HypervisorType;
import com.cloud.serializer.Param;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

@EntityReference(value = Host.class)
public class HostResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the ID of the host")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "the name of the host")
    private String name;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the state of the host")
    private HostStatus state;

    @SerializedName("disconnected")
    @Param(description = "true if the host is disconnected. False otherwise.")
    private Date disconnectedOn;

    @SerializedName(ApiConstants.TYPE)
    @Param(description = "the host type")
    private HostType hostType;

    @SerializedName("oscategoryid")
    @Param(description = "the OS category ID of the host")
    private String osCategoryId;

    @SerializedName("oscategoryname")
    @Param(description = "the OS category name of the host")
    private String osCategoryName;

    @SerializedName(ApiConstants.IP_ADDRESS)
    @Param(description = "the IP address of the host")
    private String ipAddress;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "the Zone ID of the host")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "the Zone name of the host")
    private String zoneName;

    @SerializedName(ApiConstants.POD_ID)
    @Param(description = "the Pod ID of the host")
    private String podId;

    @SerializedName("podname")
    @Param(description = "the Pod name of the host")
    private String podName;

    @SerializedName("version")
    @Param(description = "the host version")
    private String version;

    @SerializedName(ApiConstants.HYPERVISOR)
    @Param(description = "the host hypervisor")
    private HypervisorType hypervisor;

    @SerializedName("cpusockets")
    @Param(description = "the number of CPU sockets on the host")
    private Integer cpuSockets;

    @SerializedName("cpunumber")
    @Param(description = "the CPU number of the host")
    private Integer cpuNumber;

    @SerializedName("cpunumberht")
    @Param(description = "the CPU number of the host including Hyper-Threading")
    private Integer cpuNumberHyperThreading;

    @SerializedName("cpuallocated")
    @Param(description = "the amount of the host's CPU currently allocated")
    private String cpuAllocated;

    @SerializedName("cpuused")
    @Param(description = "the amount of the host's CPU currently used")
    private String cpuUsed;

    @SerializedName("cpuwithoverprovisioning")
    @Param(description = "the amount of the host's CPU after applying the cpu.overprovisioning.factor ")
    private String cpuWithOverprovisioning;

    @SerializedName("averageload")
    @Param(description = "the cpu average load on the host")
    private Long averageLoad;

    @SerializedName("networkkbsread")
    @Param(description = "the incoming network traffic on the host")
    private Long networkKbsRead;

    @SerializedName("networkkbswrite")
    @Param(description = "the outgoing network traffic on the host")
    private Long networkKbsWrite;

    @SerializedName("memorytotal")
    @Param(description = "the memory total of the host")
    private Long memoryTotal;

    @SerializedName("memoryallocated")
    @Param(description = "the amount of the host's memory currently allocated")
    private Long memoryAllocated;

    @SerializedName("memoryused")
    @Param(description = "the amount of the host's memory currently used")
    private Long memoryUsed;

    @SerializedName(ApiConstants.GPUGROUP)
    @Param(description = "GPU cards present in the host", responseObject = GpuResponse.class, since = "4.4")
    private List<GpuResponse> gpuGroup;

    @SerializedName("disksizetotal")
    @Param(description = "the total disk size of the host")
    private Long diskSizeTotal;

    @SerializedName("disksizeallocated")
    @Param(description = "the host's currently allocated disk size")
    private Long diskSizeAllocated;

    @SerializedName("capabilities")
    @Param(description = "capabilities of the host")
    private String capabilities;

    @SerializedName("lastpinged")
    @Param(description = "the date and time the host was last pinged")
    private Date lastPinged;

    @SerializedName("managementserverid")
    @Param(description = "the management server ID of the host")
    private Long managementServerId;

    @SerializedName("clusterid")
    @Param(description = "the cluster ID of the host")
    private String clusterId;

    @SerializedName("clustername")
    @Param(description = "the cluster name of the host")
    private String clusterName;

    @SerializedName("clustertype")
    @Param(description = "the cluster type of the cluster that host belongs to")
    private String clusterType;

    @SerializedName("islocalstorageactive")
    @Param(description = "true if local storage is active, false otherwise")
    private Boolean localStorageActive;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the date and time the host was created")
    private Date created;

    @SerializedName("removed")
    @Param(description = "the date and time the host was removed")
    private Date removed;

    @SerializedName("events")
    @Param(description = "events available for the host")
    private String events;

    @SerializedName("hosttags")
    @Param(description = "comma-separated list of tags for the host")
    private String hostTags;

    @SerializedName("hasenoughcapacity")
    @Param(description = "true if this host has enough CPU and RAM capacity to migrate a VM to it, false otherwise")
    private Boolean hasEnoughCapacity;

    @SerializedName("suitableformigration")
    @Param(description = "true if this host is suitable(has enough capacity and satisfies all conditions like hosttags, max guests vm limit etc) to migrate a VM to it , false " +
            "otherwise")
    private Boolean suitableForMigration;

    @SerializedName("resourcestate")
    @Param(description = "the resource state of the host")
    private String resourceState;

    @SerializedName(ApiConstants.HYPERVISOR_VERSION)
    @Param(description = "the hypervisor version")
    private String hypervisorVersion;

    @SerializedName(ApiConstants.HA_HOST)
    @Param(description = "true if the host is Ha host (dedicated to vms started by HA process; false otherwise")
    private Boolean haHost;

    @SerializedName(ApiConstants.DETAILS)
    @Param(description = "Host details in key/value pairs.", since = "4.5")
    private Map details;

    @SerializedName(ApiConstants.DEDICATED)
    @Param(description = "Is the host dedicated?")
    private Boolean dedicated = Boolean.FALSE;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "Domain ID to which the host is dedicated")
    private String domainId;

    @SerializedName(ApiConstants.DOMAIN_NAME)
    @Param(description = "Domain name to which the host is dedicated")
    private String domainName;

    @SerializedName(ApiConstants.ACCOUNT_ID)
    @Param(description = "Account ID to which the host is dedicated")
    private String accountId;

    @SerializedName(ApiConstants.ACCOUNT_NAME)
    @Param(description = "Account name to which the host is dedicated")
    private String accountName;

    @SerializedName(ApiConstants.AFFINITY_GROUP_ID)
    @Param(description = "Affinity group ID to which the host is dedicated")
    private String affinityGroupId;

    @SerializedName(ApiConstants.AFFINITY_GROUP_NAME)
    @Param(description = "Affinity group name to which the host is dedicated")
    private String affinityGroupName;

    // Default visibility to support accessing the details from unit tests
    Map getDetails() {
        return details;
    }

    public void setDetails(final Map details) {

        if (details == null) {
            return;
        }

        final Map detailsCopy = new HashMap(details);

        // Fix for CVE ID 2015-3251
        // Remove sensitive host credential information from
        // the details to prevent leakage through API calls
        detailsCopy.remove("username");
        detailsCopy.remove("password");

        this.details = detailsCopy;
    }

    @Override
    public String getObjectId() {
        return this.getId();
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public void setState(final HostStatus state) {
        this.state = state;
    }

    public void setDisconnectedOn(final Date disconnectedOn) {
        this.disconnectedOn = disconnectedOn;
    }

    public void setHostType(final HostType hostType) {
        this.hostType = hostType;
    }

    public void setOsCategoryId(final String osCategoryId) {
        this.osCategoryId = osCategoryId;
    }

    public void setOsCategoryName(final String osCategoryName) {
        this.osCategoryName = osCategoryName;
    }

    public void setIpAddress(final String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setZoneId(final String zoneId) {
        this.zoneId = zoneId;
    }

    public void setZoneName(final String zoneName) {
        this.zoneName = zoneName;
    }

    public void setPodId(final String podId) {
        this.podId = podId;
    }

    public void setPodName(final String podName) {
        this.podName = podName;
    }

    public void setVersion(final String version) {
        this.version = version;
    }

    public void setHypervisor(final HypervisorType hypervisor) {
        this.hypervisor = hypervisor;
    }

    public void setCpuSockets(final Integer cpuSockets) {
        this.cpuSockets = cpuSockets;
    }

    public void setCpuNumber(final Integer cpuNumber) {
        this.cpuNumber = cpuNumber;
    }

    public void setCpuNumberHyperThreading(Integer cpuNumberHyperThreading) {
        this.cpuNumberHyperThreading = cpuNumberHyperThreading;
    }

    public String getCpuAllocated() {
        return cpuAllocated;
    }

    public void setCpuAllocated(final String cpuAllocated) {
        this.cpuAllocated = cpuAllocated;
    }

    public void setCpuUsed(final String cpuUsed) {
        this.cpuUsed = cpuUsed;
    }

    public void setAverageLoad(final Long averageLoad) {
        this.averageLoad = averageLoad;
    }

    public void setNetworkKbsRead(final Long networkKbsRead) {
        this.networkKbsRead = networkKbsRead;
    }

    public void setNetworkKbsWrite(final Long networkKbsWrite) {
        this.networkKbsWrite = networkKbsWrite;
    }

    public void setMemoryTotal(final Long memoryTotal) {
        this.memoryTotal = memoryTotal;
    }

    public void setMemoryAllocated(final Long memoryAllocated) {
        this.memoryAllocated = memoryAllocated;
    }

    public void setMemoryUsed(final Long memoryUsed) {
        this.memoryUsed = memoryUsed;
    }

    public void setGpuGroups(final List<GpuResponse> gpuGroup) {
        this.gpuGroup = gpuGroup;
    }

    public void setDiskSizeTotal(final Long diskSizeTotal) {
        this.diskSizeTotal = diskSizeTotal;
    }

    public void setDiskSizeAllocated(final Long diskSizeAllocated) {
        this.diskSizeAllocated = diskSizeAllocated;
    }

    public void setCapabilities(final String capabilities) {
        this.capabilities = capabilities;
    }

    public void setLastPinged(final Date lastPinged) {
        this.lastPinged = lastPinged;
    }

    public void setManagementServerId(final Long managementServerId) {
        this.managementServerId = managementServerId;
    }

    public void setClusterId(final String clusterId) {
        this.clusterId = clusterId;
    }

    public void setClusterName(final String clusterName) {
        this.clusterName = clusterName;
    }

    public void setClusterType(final String clusterType) {
        this.clusterType = clusterType;
    }

    public void setLocalStorageActive(final Boolean localStorageActive) {
        this.localStorageActive = localStorageActive;
    }

    public void setCreated(final Date created) {
        this.created = created;
    }

    public void setRemoved(final Date removed) {
        this.removed = removed;
    }

    public void setEvents(final String events) {
        this.events = events;
    }

    public String getHostTags() {
        return hostTags;
    }

    public void setHostTags(final String hostTags) {
        this.hostTags = hostTags;
    }

    public void setHasEnoughCapacity(final Boolean hasEnoughCapacity) {
        this.hasEnoughCapacity = hasEnoughCapacity;
    }

    public void setSuitableForMigration(final Boolean suitableForMigration) {
        this.suitableForMigration = suitableForMigration;
    }

    public String getResourceState() {
        return resourceState;
    }

    public void setResourceState(final String resourceState) {
        this.resourceState = resourceState;
    }

    public String getCpuWithOverprovisioning() {
        return cpuWithOverprovisioning;
    }

    public void setCpuWithOverprovisioning(final String cpuWithOverprovisioning) {
        this.cpuWithOverprovisioning = cpuWithOverprovisioning;
    }

    public void setHypervisorVersion(final String hypervisorVersion) {
        this.hypervisorVersion = hypervisorVersion;
    }

    public void setHaHost(final Boolean haHost) {
        this.haHost = haHost;
    }

    public void setDedicated(final boolean dedicated) {
        this.dedicated = dedicated;
    }

    public void setDomainId(final String domainId) {
        this.domainId = domainId;
    }

    public void setDomainName(final String domainName) {
        this.domainName = domainName;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    public void setAccountName(final String accountName) {
        this.accountName = accountName;
    }

    public void setAffinityGroupId(final String affinityGroupId) {
        this.affinityGroupId = affinityGroupId;
    }

    public void setAffinityGroupName(final String affinityGroupName) {
        this.affinityGroupName = affinityGroupName;
    }
}
