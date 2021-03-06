package com.cloud.resourcedetail.dao;

import com.cloud.resourcedetail.ResourceDetailsDaoBase;
import com.cloud.resourcedetail.VpcGatewayDetailVO;

import org.springframework.stereotype.Component;

@Component
public class VpcGatewayDetailsDaoImpl extends ResourceDetailsDaoBase<VpcGatewayDetailVO> implements VpcGatewayDetailsDao {

    @Override
    public void addDetail(final long resourceId, final String key, final String value, final boolean display) {
        super.addDetail(new VpcGatewayDetailVO(resourceId, key, value, display));
    }
}
