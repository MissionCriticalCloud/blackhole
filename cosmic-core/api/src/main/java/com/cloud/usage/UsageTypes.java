package com.cloud.usage;

import com.cloud.api.response.UsageTypeResponse;

import java.util.ArrayList;
import java.util.List;

public class UsageTypes {
    /* Any changes here should also reflect in cloud_usage.quota_mapping table */
    public static final int RUNNING_VM = 1;
    public static final int ALLOCATED_VM = 2; // used for tracking how long storage has been allocated for a VM
    public static final int IP_ADDRESS = 3;
    public static final int NETWORK_BYTES_SENT = 4;
    public static final int NETWORK_BYTES_RECEIVED = 5;
    public static final int VOLUME = 6;
    public static final int TEMPLATE = 7;
    public static final int ISO = 8;
    public static final int SNAPSHOT = 9;
    public static final int SECURITY_GROUP = 10;
    public static final int LOAD_BALANCER_POLICY = 11;
    public static final int PORT_FORWARDING_RULE = 12;
    public static final int NETWORK_OFFERING = 13;
    public static final int VPN_USERS = 14;
    public static final int VM_DISK_IO_READ = 21;
    public static final int VM_DISK_IO_WRITE = 22;
    public static final int VM_DISK_BYTES_READ = 23;
    public static final int VM_DISK_BYTES_WRITE = 24;
    public static final int VM_SNAPSHOT = 25;

    public static List<UsageTypeResponse> listUsageTypes() {
        final List<UsageTypeResponse> responseList = new ArrayList<>();
        responseList.add(new UsageTypeResponse(RUNNING_VM, "Running Vm Usage"));
        responseList.add(new UsageTypeResponse(ALLOCATED_VM, "Allocated Vm Usage"));
        responseList.add(new UsageTypeResponse(IP_ADDRESS, "IP Address Usage"));
        responseList.add(new UsageTypeResponse(NETWORK_BYTES_SENT, "Network Usage (Bytes Sent)"));
        responseList.add(new UsageTypeResponse(NETWORK_BYTES_RECEIVED, "Network Usage (Bytes Received)"));
        responseList.add(new UsageTypeResponse(VOLUME, "Volume Usage"));
        responseList.add(new UsageTypeResponse(TEMPLATE, "Template Usage"));
        responseList.add(new UsageTypeResponse(ISO, "ISO Usage"));
        responseList.add(new UsageTypeResponse(SNAPSHOT, "Snapshot Usage"));
        responseList.add(new UsageTypeResponse(SECURITY_GROUP, "Security Group Usage"));
        responseList.add(new UsageTypeResponse(LOAD_BALANCER_POLICY, "Load Balancer Usage"));
        responseList.add(new UsageTypeResponse(PORT_FORWARDING_RULE, "Port Forwarding Usage"));
        responseList.add(new UsageTypeResponse(NETWORK_OFFERING, "Network Offering Usage"));
        responseList.add(new UsageTypeResponse(VPN_USERS, "VPN users usage"));
        responseList.add(new UsageTypeResponse(VM_DISK_IO_READ, "VM Disk usage(I/O Read)"));
        responseList.add(new UsageTypeResponse(VM_DISK_IO_WRITE, "VM Disk usage(I/O Write)"));
        responseList.add(new UsageTypeResponse(VM_DISK_BYTES_READ, "VM Disk usage(Bytes Read)"));
        responseList.add(new UsageTypeResponse(VM_DISK_BYTES_WRITE, "VM Disk usage(Bytes Write)"));
        responseList.add(new UsageTypeResponse(VM_SNAPSHOT, "VM Snapshot storage usage"));
        return responseList;
    }
}