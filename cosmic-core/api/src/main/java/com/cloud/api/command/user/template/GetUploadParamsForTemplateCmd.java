package com.cloud.api.command.user.template;

import com.cloud.api.APICommand;
import com.cloud.api.APICommandGroup;
import com.cloud.api.AbstractGetUploadParamsCmd;
import com.cloud.api.ApiConstants;
import com.cloud.api.ApiErrorCode;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.GetUploadParamsResponse;
import com.cloud.api.response.GuestOSResponse;
import com.cloud.context.CallContext;
import com.cloud.legacymodel.exceptions.ResourceAllocationException;

import java.net.MalformedURLException;
import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "getUploadParamsForTemplate", group = APICommandGroup.TemplateService, description = "upload an existing template into the CloudStack cloud. ", responseObject =
        GetUploadParamsResponse.class, since =
        "4.6.0", requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class GetUploadParamsForTemplateCmd extends AbstractGetUploadParamsCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(GetUploadParamsForTemplateCmd.class.getName());

    private static final String s_name = "postuploadtemplateresponse";

    @Parameter(name = ApiConstants.DISPLAY_TEXT, type = CommandType.STRING, required = true, description = "the display text of the template. This is usually used for display " +
            "purposes.", length = 4096)
    private String displayText;

    @Parameter(name = ApiConstants.HYPERVISOR, type = CommandType.STRING, required = true, description = "the target hypervisor for the template")
    private String hypervisor;

    @Parameter(name = ApiConstants.OS_TYPE_ID, type = CommandType.UUID, entityType = GuestOSResponse.class, required = true, description = "the ID of the OS Type that best " +
            "represents the OS of this template.")
    private Long osTypeId;

    @Parameter(name = ApiConstants.BITS, type = CommandType.INTEGER, description = "32 or 64 bits support. 64 by default")
    private Integer bits;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP, description = "Template details in key/value pairs.")
    private Map details;

    @Parameter(name = ApiConstants.IS_DYNAMICALLY_SCALABLE, type = CommandType.BOOLEAN, description = "true if template contains XS tools inorder to support dynamic scaling of " +
            "VM cpu/memory")
    private Boolean isDynamicallyScalable;

    @Parameter(name = ApiConstants.IS_EXTRACTABLE, type = CommandType.BOOLEAN, description = "true if the template or its derivatives are extractable; default is false")
    private Boolean extractable;

    @Parameter(name = ApiConstants.IS_FEATURED, type = CommandType.BOOLEAN, description = "true if this template is a featured template, false otherwise")
    private Boolean featured;

    @Parameter(name = ApiConstants.IS_PUBLIC, type = CommandType.BOOLEAN, description = "true if the template is available to all accounts; default is true")
    private Boolean publicTemplate;

    @Parameter(name = ApiConstants.ROUTING, type = CommandType.BOOLEAN, description = "true if the template type is routing i.e., if template is used to deploy router")
    private Boolean isRoutingType;

    @Parameter(name = ApiConstants.PASSWORD_ENABLED, type = CommandType.BOOLEAN, description = "true if the template supports the password reset feature; default is false")
    private Boolean passwordEnabled;

    @Parameter(name = ApiConstants.SSHKEY_ENABLED, type = CommandType.BOOLEAN, description = "true if the template supports the sshkey upload feature; default is false")
    private Boolean sshKeyEnabled;

    @Parameter(name = ApiConstants.TEMPLATE_TAG, type = CommandType.STRING, description = "the tag for this template.")
    private String templateTag;

    public String getDisplayText() {
        return displayText;
    }

    public String getHypervisor() {
        return hypervisor;
    }

    public Long getOsTypeId() {
        return osTypeId;
    }

    public Integer getBits() {
        return bits;
    }

    public Map getDetails() {
        if (details == null || details.isEmpty()) {
            return null;
        }
        final Collection paramsCollection = details.values();
        final Map params = (Map) paramsCollection.toArray()[0];
        return params;
    }

    public Boolean isDynamicallyScalable() {
        if (isDynamicallyScalable == null) {
            return Boolean.FALSE;
        }
        return isDynamicallyScalable;
    }

    public Boolean isExtractable() {
        return extractable;
    }

    public Boolean isFeatured() {
        return featured;
    }

    public Boolean isPublic() {
        return publicTemplate;
    }

    public Boolean isRoutingType() {
        return isRoutingType;
    }

    public Boolean isPasswordEnabled() {
        return passwordEnabled;
    }

    public Boolean isSshKeyEnabled() {
        return sshKeyEnabled;
    }

    public String getTemplateTag() {
        return templateTag;
    }

    @Override
    public void execute() throws ServerApiException {
        validateRequest();
        try {
            final GetUploadParamsResponse response = _templateService.registerTemplateForPostUpload(this);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (ResourceAllocationException | MalformedURLException e) {
            s_logger.error("exception while registering template", e);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "exception while registering template: " + e.getMessage());
        }
    }

    private void validateRequest() {
        if (getZoneId() <= 0) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "invalid zoneid");
        }
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        final Long accountId = _accountService.finalyzeAccountId(getAccountName(), getDomainId(), getProjectId(), true);
        if (accountId == null) {
            return CallContext.current().getCallingAccount().getId();
        }
        return accountId;
    }
}
