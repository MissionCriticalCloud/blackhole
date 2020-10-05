package com.cloud.storage.image.store;

import com.cloud.engine.subsystem.api.storage.DataObjectInStore;
import com.cloud.engine.subsystem.api.storage.DataStore;
import com.cloud.engine.subsystem.api.storage.PrimaryDataStore;
import com.cloud.engine.subsystem.api.storage.TemplateInfo;
import com.cloud.legacymodel.communication.answer.Answer;
import com.cloud.legacymodel.communication.answer.CopyCmdAnswer;
import com.cloud.legacymodel.exceptions.CloudRuntimeException;
import com.cloud.legacymodel.exceptions.ConcurrentOperationException;
import com.cloud.legacymodel.exceptions.NoTransitionException;
import com.cloud.legacymodel.storage.ObjectInDataStoreStateMachine;
import com.cloud.legacymodel.storage.TemplateType;
import com.cloud.legacymodel.storage.VMTemplateStatus;
import com.cloud.legacymodel.storage.VirtualMachineTemplate;
import com.cloud.legacymodel.to.DataStoreTO;
import com.cloud.legacymodel.to.DataTO;
import com.cloud.legacymodel.to.TemplateObjectTO;
import com.cloud.model.enumeration.DataObjectType;
import com.cloud.model.enumeration.DataStoreRole;
import com.cloud.model.enumeration.HypervisorType;
import com.cloud.model.enumeration.ImageFormat;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.datastore.ObjectInDataStoreManager;
import com.cloud.storage.datastore.db.TemplateDataStoreDao;
import com.cloud.storage.datastore.db.TemplateDataStoreVO;
import com.cloud.utils.component.ComponentContext;

import javax.inject.Inject;
import java.util.Date;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TemplateObject implements TemplateInfo {
    private static final Logger s_logger = LoggerFactory.getLogger(TemplateObject.class);
    @Inject
    VMTemplateDao imageDao;
    @Inject
    ObjectInDataStoreManager objectInStoreMgr;
    @Inject
    VMTemplatePoolDao templatePoolDao;
    @Inject
    TemplateDataStoreDao templateStoreDao;
    private VMTemplateVO imageVO;
    private DataStore dataStore;
    private String url;
    private String installPath; // temporarily set installPath before passing to resource for entries with empty installPath for object store migration case

    public TemplateObject() {
    }

    public static TemplateObject getTemplate(final VMTemplateVO vo, final DataStore store) {
        final TemplateObject to = ComponentContext.inject(TemplateObject.class);
        to.configure(vo, store);
        return to;
    }

    protected void configure(final VMTemplateVO template, final DataStore dataStore) {
        imageVO = template;
        this.dataStore = dataStore;
    }

    public VMTemplateVO getImage() {
        return imageVO;
    }

    @Override
    public String getUniqueName() {
        return imageVO.getUniqueName();
    }

    public void setSize(final Long size) {
        imageVO.setSize(size);
    }

    @Override
    public String getInstallPath() {
        if (installPath != null) {
            return installPath;
        }

        if (dataStore == null) {
            return null;
        }

        // managed primary data stores should not have an install path
        if (dataStore instanceof PrimaryDataStore) {
            final PrimaryDataStore primaryDataStore = (PrimaryDataStore) dataStore;

            final Map<String, String> details = primaryDataStore.getDetails();

            final boolean managed = details != null && Boolean.parseBoolean(details.get(PrimaryDataStore.MANAGED));

            if (managed) {
                return null;
            }
        }

        final DataObjectInStore obj = objectInStoreMgr.findObject(this, dataStore);
        return obj.getInstallPath();
    }

    public void setInstallPath(final String installPath) {
        this.installPath = installPath;
    }

    @Override
    public State getState() {
        return imageVO.getState();
    }

    @Override
    public DataStore getDataStore() {
        return dataStore;
    }

    @Override
    public boolean isFeatured() {
        return imageVO.isFeatured();
    }

    @Override
    public boolean isPublicTemplate() {
        return imageVO.isPublicTemplate();
    }

    @Override
    public boolean isExtractable() {
        return imageVO.isExtractable();
    }

    @Override
    public long getId() {
        return imageVO.getId();
    }

    @Override
    public String getName() {
        return imageVO.getName();
    }

    @Override
    public ImageFormat getFormat() {
        return imageVO.getFormat();
    }

    @Override
    public String getUuid() {
        return imageVO.getUuid();
    }

    @Override
    public String getDisplayText() {
        return imageVO.getDisplayText();
    }

    @Override
    public boolean getEnablePassword() {
        return imageVO.getEnablePassword();
    }

    @Override
    public String getUri() {
        if (url != null) {
            return url;
        }
        final VMTemplateVO image = imageDao.findById(imageVO.getId());

        return image.getUrl();
    }

    @Override
    public boolean getEnableSshKey() {
        return imageVO.getEnableSshKey();
    }

    @Override
    public boolean isCrossZones() {
        return imageVO.isCrossZones();
    }

    @Override
    public Long getSize() {
        if (dataStore == null) {
            return imageVO.getSize();
        }

        final VMTemplateVO image = imageDao.findById(imageVO.getId());
        return image.getSize();
    }

    @Override
    public Date getCreated() {
        return imageVO.getCreated();
    }

    @Override
    public long getGuestOSId() {
        return imageVO.getGuestOSId();
    }

    @Override
    public DataObjectType getType() {
        return DataObjectType.TEMPLATE;
    }

    @Override
    public boolean isBootable() {
        return imageVO.isBootable();
    }

    @Override
    public TemplateType getTemplateType() {
        return imageVO.getTemplateType();
    }

    @Override
    public HypervisorType getHypervisorType() {
        return imageVO.getHypervisorType();
    }

    @Override
    public void processEvent(final ObjectInDataStoreStateMachine.Event event) {
        try {
            objectInStoreMgr.update(this, event);
        } catch (final NoTransitionException e) {
            throw new CloudRuntimeException("Failed to update state", e);
        } catch (final ConcurrentOperationException e) {
            throw new CloudRuntimeException("Failed to update state", e);
        } finally {
            // in case of OperationFailed, expunge the entry
            if (event == ObjectInDataStoreStateMachine.Event.OperationFailed) {
                objectInStoreMgr.deleteIfNotReady(this);
            }
        }
    }

    @Override
    public int getBits() {
        return imageVO.getBits();
    }

    @Override
    public String getUrl() {
        if (url != null) {
            return url;
        }
        return imageVO.getUrl();
    }

    @Override
    public void processEvent(final ObjectInDataStoreStateMachine.Event event, final Answer answer) {
        try {
            if (getDataStore().getRole() == DataStoreRole.Primary) {
                if (answer instanceof CopyCmdAnswer) {
                    final CopyCmdAnswer cpyAnswer = (CopyCmdAnswer) answer;
                    final TemplateObjectTO newTemplate = (TemplateObjectTO) cpyAnswer.getNewData();
                    final VMTemplateStoragePoolVO templatePoolRef = templatePoolDao.findByPoolTemplate(getDataStore().getId(), getId());
                    templatePoolRef.setDownloadPercent(100);
                    if (newTemplate.getSize() != null) {
                        templatePoolRef.setTemplateSize(newTemplate.getSize());
                    }
                    templatePoolRef.setDownloadState(VMTemplateStatus.DOWNLOADED);
                    templatePoolRef.setLocalDownloadPath(newTemplate.getPath());
                    templatePoolRef.setInstallPath(newTemplate.getPath());
                    templatePoolDao.update(templatePoolRef.getId(), templatePoolRef);
                }
            } else if (getDataStore().getRole() == DataStoreRole.Image || getDataStore().getRole() == DataStoreRole.ImageCache) {
                if (answer instanceof CopyCmdAnswer) {
                    final CopyCmdAnswer cpyAnswer = (CopyCmdAnswer) answer;
                    final TemplateObjectTO newTemplate = (TemplateObjectTO) cpyAnswer.getNewData();
                    final TemplateDataStoreVO templateStoreRef = templateStoreDao.findByStoreTemplate(getDataStore().getId(), getId());
                    templateStoreRef.setInstallPath(newTemplate.getPath());
                    templateStoreRef.setDownloadPercent(100);
                    templateStoreRef.setDownloadState(VMTemplateStatus.DOWNLOADED);
                    templateStoreRef.setSize(newTemplate.getSize());
                    if (newTemplate.getPhysicalSize() != null) {
                        templateStoreRef.setPhysicalSize(newTemplate.getPhysicalSize());
                    }
                    templateStoreDao.update(templateStoreRef.getId(), templateStoreRef);
                    if (getDataStore().getRole() == DataStoreRole.Image) {
                        final VMTemplateVO templateVO = imageDao.findById(getId());
                        if (newTemplate.getFormat() != null) {
                            templateVO.setFormat(newTemplate.getFormat());
                        }
                        if (newTemplate.getName() != null) {
                            // For template created from snapshot, template name is determine by resource code.
                            templateVO.setUniqueName(newTemplate.getName());
                        }
                        if (newTemplate.getHypervisorType() != null) {
                            templateVO.setHypervisorType(newTemplate.getHypervisorType());
                        }
                        templateVO.setSize(newTemplate.getSize());
                        imageDao.update(templateVO.getId(), templateVO);
                    }
                }
            }
            objectInStoreMgr.update(this, event);
        } catch (final NoTransitionException e) {
            s_logger.debug("failed to update state", e);
            throw new CloudRuntimeException("Failed to update state" + e.toString());
        } catch (final Exception ex) {
            s_logger.debug("failed to process event and answer", ex);
            objectInStoreMgr.delete(this);
            throw new CloudRuntimeException("Failed to process event", ex);
        } finally {
            // in case of OperationFailed, expunge the entry
            if (event == ObjectInDataStoreStateMachine.Event.OperationFailed) {
                objectInStoreMgr.deleteIfNotReady(this);
            }
        }
    }

    public void setUrl(final String url) {
        this.url = url;
    }

    @Override
    public String getChecksum() {
        return imageVO.getChecksum();
    }

    @Override
    public void incRefCount() {
        if (dataStore == null) {
            return;
        }

        if (dataStore.getRole() == DataStoreRole.Image || dataStore.getRole() == DataStoreRole.ImageCache) {
            final TemplateDataStoreVO store = templateStoreDao.findByStoreTemplate(dataStore.getId(), getId());
            store.incrRefCnt();
            store.setLastUpdated(new Date());
            templateStoreDao.update(store.getId(), store);
        }
    }

    @Override
    public Long getSourceTemplateId() {
        return imageVO.getSourceTemplateId();
    }

    @Override
    public String getTemplateTag() {
        return imageVO.getTemplateTag();
    }

    @Override
    public void decRefCount() {
        if (dataStore == null) {
            return;
        }
        if (dataStore.getRole() == DataStoreRole.Image || dataStore.getRole() == DataStoreRole.ImageCache) {
            final TemplateDataStoreVO store = templateStoreDao.findByStoreTemplate(dataStore.getId(), getId());
            store.decrRefCnt();
            store.setLastUpdated(new Date());
            templateStoreDao.update(store.getId(), store);
        }
    }

    @Override
    public Map getDetails() {
        return imageVO.getDetails();
    }

    @Override
    public boolean isDynamicallyScalable() {
        return false;
    }

    @Override
    public Long getRefCount() {
        if (dataStore == null) {
            return null;
        }
        if (dataStore.getRole() == DataStoreRole.Image || dataStore.getRole() == DataStoreRole.ImageCache) {
            final TemplateDataStoreVO store = templateStoreDao.findByStoreTemplate(dataStore.getId(), getId());
            return store.getRefCnt();
        }
        return null;
    }

    @Override
    public long getUpdatedCount() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void incrUpdatedCount() {
        // TODO Auto-generated method stub
    }

    @Override
    public DataTO getTO() {
        DataTO to = null;
        DataStoreTO dataStoreTO = null;
        if (this.getDataStore() != null) {
            dataStoreTO = this.getDataStore().getTO();
        }
        if (dataStore == null) {
            to = new TemplateObjectTO(this.getInstallPath(), this.getUrl(), this.getUuid(), this.getId(), this.getFormat(), this.getAccountId(), this.getChecksum(), this.getDisplayText(),
                    dataStoreTO, this.getName(), this.getUniqueName(), null, null, null, this.getHypervisorType());
        } else {
            to = dataStore.getDriver().getTO(this);
            if (to == null) {
                to = new TemplateObjectTO(this.getInstallPath(), this.getUrl(), this.getUuid(), this.getId(), this.getFormat(), this.getAccountId(), this.getChecksum(), this.getDisplayText(),
                        dataStoreTO, this.getName(), this.getUniqueName(), null, null, null, this.getHypervisorType());
            }
        }

        return to;
    }

    @Override
    public Date getUpdated() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getAccountId() {
        return imageVO.getAccountId();
    }

    @Override
    public long getDomainId() {
        return imageVO.getDomainId();
    }

    @Override
    public Class<?> getEntityType() {
        return VirtualMachineTemplate.class;
    }

    @Override
    public boolean delete() {
        if (dataStore != null) {
            return dataStore.delete(this);
        }
        return true;
    }
}
