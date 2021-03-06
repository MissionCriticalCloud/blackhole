package com.cloud.network.vpc;

import com.cloud.legacymodel.InternalIdentity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "network_acl_item_cidrs")
public class NetworkACLItemCidrsVO implements InternalIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "network_acl_item_id")
    private long networkACLItemId;

    @Column(name = "cidr")
    private String cidrList;

    public NetworkACLItemCidrsVO() {
    }

    public NetworkACLItemCidrsVO(final long networkAclItemId, final String cidrList) {
        this.networkACLItemId = networkAclItemId;
        this.cidrList = cidrList;
    }

    /* (non-Javadoc)
     * @see InternalIdentity#getId()
     */
    @Override
    public long getId() {
        return id;
    }

    public long getNetworkACLItemId() {
        return networkACLItemId;
    }

    public String getCidr() {
        return cidrList;
    }

    public String getCidrList() {
        return cidrList;
    }

    public void setCidrList(final String cidrList) {
        this.cidrList = cidrList;
    }
}
