package com.cloud.network;

import com.cloud.legacymodel.Identity;
import com.cloud.legacymodel.InternalIdentity;
import com.cloud.legacymodel.utils.Pair;

import java.util.List;

/**
 *
 */
public interface PhysicalNetwork extends Identity, InternalIdentity {

    BroadcastDomainRange getBroadcastDomainRange();

    long getDataCenterId();

    State getState();

    List<String> getTags();

    List<String> getIsolationMethods();

    Long getDomainId();

    List<Pair<Integer, Integer>> getVnet();

    String getVnetString();

    String getSpeed();

    String getName();

    enum State {
        Disabled, Enabled
    }

    enum IsolationMethod {
        VLAN, L3, STT, VXLAN
    }

    enum BroadcastDomainRange {
        POD, ZONE
    }
}
