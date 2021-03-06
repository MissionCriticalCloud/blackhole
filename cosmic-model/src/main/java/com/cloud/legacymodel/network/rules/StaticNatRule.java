package com.cloud.legacymodel.network.rules;

public class StaticNatRule {
    private boolean revoke;
    private String protocol;
    private String sourceIpAddress;
    private String sourcePortRange;
    private String destinationIpAddress;

    public StaticNatRule() {
        // Empty constructor for (de)serialization
    }

    public StaticNatRule(final boolean revoke, final String protocol, final String sourceIpAddress, final String sourcePortRange, final String destinationIpAddress) {
        super();
        this.revoke = revoke;
        this.protocol = protocol;
        this.sourceIpAddress = sourceIpAddress;
        this.sourcePortRange = sourcePortRange;
        this.destinationIpAddress = destinationIpAddress;
    }

    public boolean isRevoke() {
        return this.revoke;
    }

    public void setRevoke(final boolean revoke) {
        this.revoke = revoke;
    }

    public String getProtocol() {
        return this.protocol;
    }

    public void setProtocol(final String protocol) {
        this.protocol = protocol;
    }

    public String getSourceIpAddress() {
        return this.sourceIpAddress;
    }

    public void setSourceIpAddress(final String sourceIpAddress) {
        this.sourceIpAddress = sourceIpAddress;
    }

    public String getSourcePortRange() {
        return this.sourcePortRange;
    }

    public void setSourcePortRange(final String sourcePortRange) {
        this.sourcePortRange = sourcePortRange;
    }

    public String getDestinationIpAddress() {
        return this.destinationIpAddress;
    }

    public void setDestinationIpAddress(final String destinationIpAddress) {
        this.destinationIpAddress = destinationIpAddress;
    }
}
