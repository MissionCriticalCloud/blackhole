package com.cloud.user;

import com.cloud.api.command.admin.domain.ListDomainChildrenCmd;
import com.cloud.api.command.admin.domain.ListDomainsCmd;
import com.cloud.api.command.admin.domain.UpdateDomainCmd;
import com.cloud.configuration.dao.ResourceCountDao;
import com.cloud.configuration.dao.ResourceLimitDao;
import com.cloud.context.CallContext;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.engine.orchestration.service.NetworkOrchestrationService;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.framework.messagebus.MessageBus;
import com.cloud.framework.messagebus.PublishScope;
import com.cloud.legacymodel.configuration.Resource.ResourceOwnerType;
import com.cloud.legacymodel.configuration.ResourceLimit;
import com.cloud.legacymodel.domain.Domain;
import com.cloud.legacymodel.exceptions.CloudRuntimeException;
import com.cloud.legacymodel.exceptions.ConcurrentOperationException;
import com.cloud.legacymodel.exceptions.InvalidParameterValueException;
import com.cloud.legacymodel.exceptions.PermissionDeniedException;
import com.cloud.legacymodel.exceptions.ResourceUnavailableException;
import com.cloud.legacymodel.user.Account;
import com.cloud.legacymodel.utils.Pair;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.projects.ProjectManager;
import com.cloud.projects.ProjectVO;
import com.cloud.projects.dao.ProjectDao;
import com.cloud.region.RegionManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DomainManagerImpl extends ManagerBase implements DomainManager, DomainService {
    public static final Logger s_logger = LoggerFactory.getLogger(DomainManagerImpl.class);
    @Inject
    MessageBus _messageBus;
    @Inject
    private DomainDao _domainDao;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private ResourceCountDao _resourceCountDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private ServiceOfferingDao _offeringsDao;
    @Inject
    private ProjectDao _projectDao;
    @Inject
    private ProjectManager _projectMgr;
    @Inject
    private RegionManager _regionMgr;
    @Inject
    private ResourceLimitDao _resourceLimitDao;
    @Inject
    private DedicatedResourceDao _dedicatedDao;
    @Inject
    private NetworkOrchestrationService _networkMgr;
    @Inject
    private NetworkDomainDao _networkDomainDao;

    @Override
    public Set<Long> getDomainChildrenIds(final String parentDomainPath) {
        final Set<Long> childDomains = new HashSet<>();
        final SearchCriteria<DomainVO> sc = _domainDao.createSearchCriteria();
        sc.addAnd("path", SearchCriteria.Op.LIKE, parentDomainPath + "%");

        final List<DomainVO> domains = _domainDao.search(sc, null);

        for (final DomainVO domain : domains) {
            childDomains.add(domain.getId());
        }

        return childDomains;
    }

    @Override
    @DB
    public Domain createDomain(final String name, final Long parentId, final Long ownerId, final String networkDomain, String domainUUID, final String email, final String slackChannelName) {
        // Verify network domain
        if (networkDomain != null) {
            if (!NetUtils.verifyDomainName(networkDomain)) {
                throw new InvalidParameterValueException(
                        "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters " +
                                "'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
            }
        }

        final SearchCriteria<DomainVO> sc = _domainDao.createSearchCriteria();
        sc.addAnd("name", SearchCriteria.Op.EQ, name);
        sc.addAnd("parent", SearchCriteria.Op.EQ, parentId);
        final List<DomainVO> domains = _domainDao.search(sc, null);

        if (!domains.isEmpty()) {
            throw new InvalidParameterValueException("Domain with name " + name + " already exists for the parent id=" + parentId);
        }

        if (domainUUID == null) {
            domainUUID = UUID.randomUUID().toString();
        }

        final EmailValidator validator = EmailValidator.getInstance();
        if (!StringUtils.isEmpty(email) && !validator.isValid(email)) {
            throw new InvalidParameterValueException("Email address is not formatted correctly");
        }

        final String domainUUIDFinal = domainUUID;
        final DomainVO domain = Transaction.execute(new TransactionCallback<DomainVO>() {
            @Override
            public DomainVO doInTransaction(final TransactionStatus status) {
                final DomainVO domain = _domainDao.create(new DomainVO(name, ownerId, parentId, networkDomain, domainUUIDFinal, email, slackChannelName));
                _resourceCountDao.createResourceCounts(domain.getId(), ResourceLimit.ResourceOwnerType.Domain);
                return domain;
            }
        });

        CallContext.current().putContextParameter(Domain.class, domain.getUuid());

        _messageBus.publish(_name, MESSAGE_ADD_DOMAIN_EVENT, PublishScope.LOCAL, domain.getId());

        return domain;
    }

    @Override
    public Set<Long> getDomainParentIds(final long domainId) {
        return _domainDao.getDomainParentIds(domainId);
    }

    @Override
    public boolean removeDomain(final long domainId) {
        return _domainDao.remove(domainId);
    }

    @Override
    public List<? extends Domain> findInactiveDomains() {
        return _domainDao.findInactiveDomains();
    }

    @Override
    public boolean deleteDomain(final DomainVO domain, final Boolean cleanup) {
        // mark domain as inactive
        s_logger.debug("Marking domain id=" + domain.getId() + " as " + Domain.State.Inactive + " before actually deleting it");
        domain.setState(Domain.State.Inactive);
        _domainDao.update(domain.getId(), domain);
        boolean rollBackState = false;
        boolean hasDedicatedResources = false;

        try {
            final long ownerId = domain.getAccountId();
            if ((cleanup != null) && cleanup.booleanValue()) {
                if (!cleanupDomain(domain.getId(), ownerId)) {
                    rollBackState = true;
                    final CloudRuntimeException e =
                            new CloudRuntimeException("Failed to clean up domain resources and sub domains, delete failed on domain " + domain.getName() + " (id: " +
                                    domain.getId() + ").");
                    e.addProxyObject(domain.getUuid(), "domainId");
                    throw e;
                }
            } else {
                //don't delete the domain if there are accounts set for cleanup, or non-removed networks exist, or domain has dedicated resources
                final List<Long> networkIds = _networkDomainDao.listNetworkIdsByDomain(domain.getId());
                final List<AccountVO> accountsForCleanup = _accountDao.findCleanupsForRemovedAccounts(domain.getId());
                final List<DedicatedResourceVO> dedicatedResources = _dedicatedDao.listByDomainId(domain.getId());
                if (dedicatedResources != null && !dedicatedResources.isEmpty()) {
                    s_logger.error("There are dedicated resources for the domain " + domain.getId());
                    hasDedicatedResources = true;
                }
                if (accountsForCleanup.isEmpty() && networkIds.isEmpty() && !hasDedicatedResources) {
                    if (!_domainDao.remove(domain.getId())) {
                        rollBackState = true;
                        final CloudRuntimeException e =
                                new CloudRuntimeException("Delete failed on domain " + domain.getName() + " (id: " + domain.getId() +
                                        "); Please make sure all users and sub domains have been removed from the domain before deleting");
                        e.addProxyObject(domain.getUuid(), "domainId");
                        throw e;
                    }
                } else {
                    rollBackState = true;
                    String msg = null;
                    if (!accountsForCleanup.isEmpty()) {
                        msg = accountsForCleanup.size() + " accounts to cleanup";
                    } else if (!networkIds.isEmpty()) {
                        msg = networkIds.size() + " non-removed networks";
                    } else if (hasDedicatedResources) {
                        msg = "dedicated resources.";
                    }

                    final CloudRuntimeException e = new CloudRuntimeException("Can't delete the domain yet because it has " + msg);
                    e.addProxyObject(domain.getUuid(), "domainId");
                    throw e;
                }
            }

            cleanupDomainOfferings(domain.getId());
            CallContext.current().putContextParameter(Domain.class, domain.getUuid());
            return true;
        } catch (final Exception ex) {
            s_logger.error("Exception deleting domain with id " + domain.getId(), ex);
            if (ex instanceof CloudRuntimeException) {
                throw (CloudRuntimeException) ex;
            } else {
                return false;
            }
        } finally {
            //when success is false
            if (rollBackState) {
                s_logger.debug("Changing domain id=" + domain.getId() + " state back to " + Domain.State.Active +
                        " because it can't be removed due to resources referencing to it");
                domain.setState(Domain.State.Active);
                _domainDao.update(domain.getId(), domain);
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DOMAIN_DELETE, eventDescription = "deleting Domain", async = true)
    public boolean deleteDomain(final long domainId, final Boolean cleanup) {
        final Account caller = CallContext.current().getCallingAccount();

        final DomainVO domain = _domainDao.findById(domainId);

        if (domain == null) {
            throw new InvalidParameterValueException("Failed to delete domain " + domainId + ", domain not found");
        } else if (domainId == Domain.ROOT_DOMAIN) {
            throw new PermissionDeniedException("Can't delete ROOT domain");
        }

        _accountMgr.checkAccess(caller, domain);

        return deleteDomain(domain, cleanup);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DOMAIN_UPDATE, eventDescription = "updating Domain")
    @DB
    public DomainVO updateDomain(final UpdateDomainCmd cmd) {
        final Long domainId = cmd.getId();
        final String domainName = cmd.getDomainName();
        final String networkDomain = cmd.getNetworkDomain();
        final String email = cmd.getEmail();
        final String slackChannelName = cmd.getSlackChannelName();

        // check if domain exists in the system
        final DomainVO domain = _domainDao.findById(domainId);
        if (domain == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find domain with specified domain id");
            ex.addProxyObject(domainId.toString(), "domainId");
            throw ex;
        } else if (domain.getParent() == null && domainName != null) {
            // check if domain is ROOT domain - and deny to edit it with the new name
            throw new InvalidParameterValueException("ROOT domain can not be edited with a new name");
        }

        // check permissions
        final Account caller = CallContext.current().getCallingAccount();
        _accountMgr.checkAccess(caller, domain);

        // domain name is unique in the cloud
        if (domainName != null) {
            final SearchCriteria<DomainVO> sc = _domainDao.createSearchCriteria();
            sc.addAnd("name", SearchCriteria.Op.EQ, domainName);
            final List<DomainVO> domains = _domainDao.search(sc, null);

            final boolean sameDomain = (domains.size() == 1 && domains.get(0).getId() == domainId);

            if (!domains.isEmpty() && !sameDomain) {
                final InvalidParameterValueException ex =
                        new InvalidParameterValueException("Failed to update specified domain id with name '" + domainName + "' since it already exists in the system");
                ex.addProxyObject(domain.getUuid(), "domainId");
                throw ex;
            }
        }

        // validate network domain
        if (networkDomain != null && !networkDomain.isEmpty()) {
            if (!NetUtils.verifyDomainName(networkDomain)) {
                throw new InvalidParameterValueException(
                        "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters " +
                                "'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
            }
        }

        final EmailValidator validator = EmailValidator.getInstance();
        if (!StringUtils.isEmpty(email) && !validator.isValid(email)) {
            throw new InvalidParameterValueException("Email address is not formatted correctly");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                if (domainName != null) {
                    final String updatedDomainPath = getUpdatedDomainPath(domain.getPath(), domainName);
                    updateDomainChildren(domain, updatedDomainPath);
                    domain.setName(domainName);
                    domain.setPath(updatedDomainPath);
                }

                if (networkDomain != null) {
                    if (networkDomain.isEmpty()) {
                        domain.setNetworkDomain(null);
                    } else {
                        domain.setNetworkDomain(networkDomain);
                    }
                }

                if (email != null) {
                    if (email.isEmpty()) {
                        domain.setEmail(null);
                    } else {
                        domain.setEmail(email);
                    }
                }

                if (slackChannelName != null) {
                    if (slackChannelName.isEmpty()) {
                        domain.setSlackChannelName(null);
                    } else {
                        domain.setSlackChannelName(slackChannelName);
                    }
                }

                _domainDao.update(domainId, domain);
                CallContext.current().putContextParameter(Domain.class, domain.getUuid());
            }
        });

        return _domainDao.findById(domainId);
    }

    private String getUpdatedDomainPath(final String oldPath, final String newName) {
        final String[] tokenizedPath = oldPath.split("/");
        tokenizedPath[tokenizedPath.length - 1] = newName;
        final StringBuilder finalPath = new StringBuilder();
        for (final String token : tokenizedPath) {
            finalPath.append(token);
            finalPath.append("/");
        }
        return finalPath.toString();
    }

    private void updateDomainChildren(final DomainVO domain, final String updatedDomainPrefix) {
        final List<DomainVO> domainChildren = _domainDao.findAllChildren(domain.getPath(), domain.getId());
        // for each child, update the path
        for (final DomainVO dom : domainChildren) {
            dom.setPath(dom.getPath().replaceFirst(domain.getPath(), updatedDomainPrefix));
            _domainDao.update(dom.getId(), dom);
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DOMAIN_CREATE, eventDescription = "creating Domain")
    public Domain createDomain(final String name, Long parentId, final String networkDomain, final String domainUUID, final String email, final String slackChannelName) {
        final Account caller = CallContext.current().getCallingAccount();

        if (parentId == null) {
            parentId = Long.valueOf(Domain.ROOT_DOMAIN);
        }

        final DomainVO parentDomain = _domainDao.findById(parentId);
        if (parentDomain == null) {
            throw new InvalidParameterValueException("Unable to create domain " + name + ", parent domain " + parentId + " not found.");
        }

        if (parentDomain.getState().equals(Domain.State.Inactive)) {
            throw new CloudRuntimeException("The domain cannot be created as the parent domain " + parentDomain.getName() + " is being deleted");
        }

        _accountMgr.checkAccess(caller, parentDomain);

        return createDomain(name, parentId, caller.getId(), networkDomain, domainUUID, email, slackChannelName);
    }

    @Override
    public Domain getDomain(final long domainId) {
        return _domainDao.findById(domainId);
    }

    @Override
    public Domain getDomain(final String domainUuid) {
        return _domainDao.findByUuid(domainUuid);
    }

    @Override
    public Domain getDomainByName(final String name, final long parentId) {
        final SearchCriteria<DomainVO> sc = _domainDao.createSearchCriteria();
        sc.addAnd("name", SearchCriteria.Op.EQ, name);
        sc.addAnd("parent", SearchCriteria.Op.EQ, parentId);
        final Domain domain = _domainDao.findOneBy(sc);
        return domain;
    }

    @Override
    public boolean isChildDomain(final Long parentId, final Long childId) {
        return _domainDao.isChildDomain(parentId, childId);
    }

    @Override
    public Pair<List<? extends Domain>, Integer> searchForDomains(final ListDomainsCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getId();
        final boolean listAll = cmd.listAll();
        boolean isRecursive = false;

        if (domainId != null) {
            final Domain domain = getDomain(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist");
            }
            _accountMgr.checkAccess(caller, domain);
        } else {
            if (!_accountMgr.isRootAdmin(caller.getId())) {
                domainId = caller.getDomainId();
            }
            if (listAll) {
                isRecursive = true;
            }
        }

        final Filter searchFilter = new Filter(DomainVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final String domainName = cmd.getDomainName();
        final Integer level = cmd.getLevel();
        final Object keyword = cmd.getKeyword();

        final SearchBuilder<DomainVO> sb = _domainDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("level", sb.entity().getLevel(), SearchCriteria.Op.EQ);
        sb.and("path", sb.entity().getPath(), SearchCriteria.Op.LIKE);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);

        final SearchCriteria<DomainVO> sc = sb.create();

        if (keyword != null) {
            final SearchCriteria<DomainVO> ssc = _domainDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (domainName != null) {
            sc.setParameters("name", domainName);
        }

        if (level != null) {
            sc.setParameters("level", level);
        }

        if (domainId != null) {
            if (isRecursive) {
                sc.setParameters("path", getDomain(domainId).getPath() + "%");
            } else {
                sc.setParameters("id", domainId);
            }
        }

        // return only Active domains to the API
        sc.setParameters("state", Domain.State.Active);

        final Pair<List<DomainVO>, Integer> result = _domainDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends Domain>, Integer> searchForDomainChildren(final ListDomainChildrenCmd cmd) throws PermissionDeniedException {
        Long domainId = cmd.getId();
        final String domainName = cmd.getDomainName();
        final Boolean isRecursive = cmd.isRecursive();
        final Object keyword = cmd.getKeyword();
        final boolean listAll = cmd.listAll();
        String path = null;

        final Account caller = CallContext.current().getCallingAccount();
        if (domainId != null) {
            _accountMgr.checkAccess(caller, getDomain(domainId));
        } else {
            domainId = caller.getDomainId();
        }

        final DomainVO domain = _domainDao.findById(domainId);
        if (domain != null && isRecursive && !listAll) {
            path = domain.getPath();
            domainId = null;
        }

        final Filter searchFilter = new Filter(DomainVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final Pair<List<DomainVO>, Integer> result = searchForDomainChildren(searchFilter, domainId, domainName, keyword, path, true);

        return new Pair<>(result.first(), result.second());
    }

    @Override
    public DomainVO findDomainByPath(final String domainPath) {
        return _domainDao.findDomainByPath(domainPath);
    }

    private Pair<List<DomainVO>, Integer> searchForDomainChildren(final Filter searchFilter, final Long domainId, final String domainName, final Object keyword, final String path,
                                                                  final boolean listActiveOnly) {
        final SearchCriteria<DomainVO> sc = _domainDao.createSearchCriteria();

        if (keyword != null) {
            final SearchCriteria<DomainVO> ssc = _domainDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (domainId != null) {
            sc.addAnd("parent", SearchCriteria.Op.EQ, domainId);
        }

        if (domainName != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + domainName + "%");
        }

        if (path != null) {
            sc.addAnd("path", SearchCriteria.Op.NEQ, path);
            sc.addAnd("path", SearchCriteria.Op.LIKE, path + "%");
        }

        if (listActiveOnly) {
            sc.addAnd("state", SearchCriteria.Op.EQ, Domain.State.Active);
        }

        return _domainDao.searchAndCount(sc, searchFilter);
    }

    private void cleanupDomainOfferings(final Long domainId) {
        // delete the service and disk offerings associated with this domain
        final List<DiskOfferingVO> diskOfferingsForThisDomain = _diskOfferingDao.listByDomainId(domainId);
        for (final DiskOfferingVO diskOffering : diskOfferingsForThisDomain) {
            _diskOfferingDao.remove(diskOffering.getId());
        }

        final List<ServiceOfferingVO> serviceOfferingsForThisDomain = _offeringsDao.findServiceOfferingByDomainId(domainId);
        for (final ServiceOfferingVO serviceOffering : serviceOfferingsForThisDomain) {
            _offeringsDao.remove(serviceOffering.getId());
        }
    }

    private boolean cleanupDomain(final Long domainId, final Long ownerId) throws ConcurrentOperationException, ResourceUnavailableException {
        s_logger.debug("Cleaning up domain id=" + domainId);
        boolean success = true;
        {
            final DomainVO domainHandle = _domainDao.findById(domainId);
            domainHandle.setState(Domain.State.Inactive);
            _domainDao.update(domainId, domainHandle);

            final SearchCriteria<DomainVO> sc = _domainDao.createSearchCriteria();
            sc.addAnd("parent", SearchCriteria.Op.EQ, domainId);
            final List<DomainVO> domains = _domainDao.search(sc, null);

            final SearchCriteria<DomainVO> sc1 = _domainDao.createSearchCriteria();
            sc1.addAnd("path", SearchCriteria.Op.LIKE, "%" + domainHandle.getPath() + "%");
            final List<DomainVO> domainsToBeInactivated = _domainDao.search(sc1, null);

            // update all subdomains to inactive so no accounts/users can be created
            for (final DomainVO domain : domainsToBeInactivated) {
                domain.setState(Domain.State.Inactive);
                _domainDao.update(domain.getId(), domain);
            }

            // cleanup sub-domains first
            for (final DomainVO domain : domains) {
                success = (success && cleanupDomain(domain.getId(), domain.getAccountId()));
                if (!success) {
                    s_logger.warn("Failed to cleanup domain id=" + domain.getId());
                }
            }
        }

        // delete users which will also delete accounts and release resources for those accounts
        final SearchCriteria<AccountVO> sc = _accountDao.createSearchCriteria();
        sc.addAnd("domainId", SearchCriteria.Op.EQ, domainId);
        final List<AccountVO> accounts = _accountDao.search(sc, null);
        for (final AccountVO account : accounts) {
            if (account.getType() != Account.ACCOUNT_TYPE_PROJECT) {
                s_logger.debug("Deleting account " + account + " as a part of domain id=" + domainId + " cleanup");
                final boolean deleteAccount = _accountMgr.deleteAccount(account, CallContext.current().getCallingUserId(), CallContext.current().getCallingAccount());
                if (!deleteAccount) {
                    s_logger.warn("Failed to cleanup account id=" + account.getId() + " as a part of domain cleanup");
                }
                success = (success && deleteAccount);
            } else {
                final ProjectVO project = _projectDao.findByProjectAccountId(account.getId());
                s_logger.debug("Deleting project " + project + " as a part of domain id=" + domainId + " cleanup");
                final boolean deleteProject = _projectMgr.deleteProject(CallContext.current().getCallingAccount(), CallContext.current().getCallingUserId(), project);
                if (!deleteProject) {
                    s_logger.warn("Failed to cleanup project " + project + " as a part of domain cleanup");
                }
                success = (success && deleteProject);
            }
        }

        //delete the domain shared networks
        boolean networksDeleted = true;
        s_logger.debug("Deleting networks for domain id=" + domainId);
        final List<Long> networkIds = _networkDomainDao.listNetworkIdsByDomain(domainId);
        final CallContext ctx = CallContext.current();
        final ReservationContext context = new ReservationContextImpl(null, null, _accountMgr.getActiveUser(ctx.getCallingUserId()), ctx.getCallingAccount());
        for (final Long networkId : networkIds) {
            s_logger.debug("Deleting network id=" + networkId + " as a part of domain id=" + domainId + " cleanup");
            if (!_networkMgr.destroyNetwork(networkId, context, false)) {
                s_logger.warn("Unable to destroy network id=" + networkId + " as a part of domain id=" + domainId + " cleanup.");
                networksDeleted = false;
            } else {
                s_logger.debug("Network " + networkId + " successfully deleted as a part of domain id=" + domainId + " cleanup.");
            }
        }

        //don't proceed if networks failed to cleanup. The cleanup will be performed for inactive domain once again
        if (!networksDeleted) {
            s_logger.debug("Failed to delete the shared networks as a part of domain id=" + domainId + " clenaup");
            return false;
        }

        // don't remove the domain if there are accounts required cleanup
        final boolean deleteDomainSuccess;
        final List<AccountVO> accountsForCleanup = _accountDao.findCleanupsForRemovedAccounts(domainId);
        if (accountsForCleanup.isEmpty()) {
            //release dedication if any, before deleting the domain
            final List<DedicatedResourceVO> dedicatedResources = _dedicatedDao.listByDomainId(domainId);
            if (dedicatedResources != null && !dedicatedResources.isEmpty()) {
                s_logger.debug("Releasing dedicated resources for domain" + domainId);
                for (final DedicatedResourceVO dr : dedicatedResources) {
                    if (!_dedicatedDao.remove(dr.getId())) {
                        s_logger.warn("Fail to release dedicated resources for domain " + domainId);
                        return false;
                    }
                }
            }
            //delete domain
            deleteDomainSuccess = _domainDao.remove(domainId);

            // Delete resource count and resource limits entries set for this domain (if there are any).
            _resourceCountDao.removeEntriesByOwner(domainId, ResourceOwnerType.Domain);
            _resourceLimitDao.removeEntriesByOwner(domainId, ResourceOwnerType.Domain);
        } else {
            s_logger.debug("Can't delete the domain yet because it has " + accountsForCleanup.size() + "accounts that need a cleanup");
            return false;
        }

        return success && deleteDomainSuccess;
    }
}
