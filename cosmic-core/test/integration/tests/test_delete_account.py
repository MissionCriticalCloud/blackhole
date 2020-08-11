from cs import CloudStackApiException

from cosmic.base import *
from cosmic.common import *
from cosmic.cosmicLog import CosmicLog
from cosmic.cosmicTestCase import cosmicTestCase


class TestDeleteAccount(cosmicTestCase):
    def setUp(self):
        self.logger = CosmicLog(CosmicLog.LOGGER_TEST).get_logger()
        self.testClient = self.getClsTestClient()
        self.apiclient = self.testClient.getApiClient()
        self.services = self.testClient.getParsedTestDataConfig()

        # Get Zone, Domain and templates
        self.domain = get_domain(self.apiclient)
        self.zone = get_zone(self.apiclient, self.testClient.getZoneForTests())
        template = get_template(
            self.apiclient,
            self.zone.id
        )
        self.services["virtual_machine"]["zoneid"] = self.zone.id

        # Create an account, network, VM and IP addresses
        self.account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=True,
            domainid=self.domain.id
        )

        self.vpc_offering = get_default_vpc_offering(self.apiclient)
        self.logger.debug("VPC Offering '%s' selected", self.vpc_offering.name)

        self.network_offering = get_default_network_offering(self.apiclient)
        self.logger.debug("Network Offering '%s' selected", self.network_offering.name)

        self.virtual_machine_offering = get_default_virtual_machine_offering(self.apiclient)
        self.logger.debug("Virtual Machine Offering '%s' selected", self.virtual_machine_offering.name)

        self.default_allow_acl = get_network_acl(self.apiclient, 'default_allow')
        self.logger.debug("ACL '%s' selected", self.default_allow_acl.name)

        self.template = get_template(self.apiclient, self.zone.id)
        self.logger.debug("Template '%s' selected" % self.template.name)

        self.vpc1 = VPC.create(self.apiclient,
                               self.services['vpcs']['vpc1'],
                               vpcofferingid=self.vpc_offering.id,
                               zoneid=self.zone.id,
                               domainid=self.domain.id,
                               account=self.account.name)
        self.logger.debug("VPC '%s' created, CIDR: %s", self.vpc1.name, self.vpc1.cidr)

        self.network1 = Network.create(self.apiclient,
                                       self.services['networks']['network1'],
                                       networkofferingid=self.network_offering.id,
                                       aclid=self.default_allow_acl.id,
                                       vpcid=self.vpc1.id,
                                       zoneid=self.zone.id,
                                       domainid=self.domain.id,
                                       accountid=self.account.name)
        self.logger.debug("Network '%s' created, CIDR: %s, Gateway: %s", self.network1.name, self.network1.cidr, self.network1.gateway)

        self.vm1 = VirtualMachine.create(self.apiclient,
                                         self.services['vms']['vm1'],
                                         templateid=self.template.id,
                                         serviceofferingid=self.virtual_machine_offering.id,
                                         networkids=[self.network1.id],
                                         zoneid=self.zone.id,
                                         domainid=self.domain.id,
                                         accountid=self.account.name)
        self.logger.debug("VM '%s' created, Network: %s, IP %s", self.vm1.name, self.network1.name, self.vm1.ipaddress)

        src_nat_ip_addrs = list_public_ip(
            self.apiclient,
            account=self.account.name,
            domainid=self.account.domainid
        )

        try:
            src_nat_ip_addr = src_nat_ip_addrs[0]
        except Exception as e:
            self.fail("SSH failed for VM with IP: %s %s" %
                      (src_nat_ip_addr.ipaddress, e))

        self.lb_rule = LoadBalancerRule.create(
            self.apiclient,
            self.services["lbrule"],
            src_nat_ip_addr.id,
            self.account.name,
            self.network1.id,
            self.vpc1.id
        )
        self.lb_rule.assign(self.apiclient, [self.vm1])

        self.nat_rule = NATRule.create(
            self.apiclient,
            self.vm1,
            self.services["natrule"],
            src_nat_ip_addr.id
        )
        self.cleanup = [self.account]
        return

    def tearDown(self):
        cleanup_resources(self.apiclient, self.cleanup)
        return

    @attr(tags=['advanced'])
    def test_01_getsecretkey(self):
        """Test if we can get the secretkey"""

        userkeys = None
        userinfo = None

        try:
            userkeys = User.registerUserKeys(self.apiclient, self.account.user[0].id)
        except CloudStackApiException:
            self.logger.debug("Registered user keys")
        except Exception as e:
            self.logger.debug("Exception %s raised while registering user keys" % e)

        try:
            userinfo = User.list(self.apiclient, id=self.account.user[0].id)[0]
        except CloudStackApiException:
            self.logger.debug("Retrieved user")
        except Exception as e:
            self.logger.debug("Exception %s raised while retrieving user" % e)

        self.assertEqual(
            userkeys.apikey,
            userinfo.apikey,
            "API key is different"
        )
        self.assertNotEqual(
            userkeys.secretkey,
            userinfo.secretkey,
            "Secret key is visible"
        )
        return

    @attr(tags=['advanced'])
    def test_02_delete_account(self):
        """Test for delete account"""

        # Validate the Following
        # 1. after account.cleanup.interval (global setting)
        #    time all the PF/LB rules should be deleted
        # 2. verify that list(LoadBalancer/PortForwarding)Rules
        #    API does not return any rules for the account
        # 3. The domR should have been expunged for this account

        self.account.delete(self.apiclient)
        interval = list_configurations(
            self.apiclient,
            name='account.cleanup.interval'
        )
        self.assertEqual(
            isinstance(interval, list),
            True,
            "Check if account.cleanup.interval config present"
        )
        # Sleep to ensure that all resources are deleted
        time.sleep(int(interval[0].value))

        # ListLoadBalancerRules should not list
        # associated rules with deleted account
        # Unable to find account testuser1 in domain 1 : Exception
        try:
            list_lb_rules(
                self.apiclient,
                account=self.account.name,
                domainid=self.account.domainid
            )
        except CloudStackApiException:
            self.logger.debug("Port Forwarding Rule is deleted")

        # ListPortForwardingRules should not
        # list associated rules with deleted account
        try:
            list_nat_rules(
                self.apiclient,
                account=self.account.name,
                domainid=self.account.domainid
            )
        except CloudStackApiException:
            self.logger.debug("NATRule is deleted")

        # Retrieve router for the user account
        try:
            routers = list_routers(
                self.apiclient,
                account=self.account.name,
                domainid=self.account.domainid
            )
            self.assertEqual(
                routers,
                None,
                "Check routers are properly deleted."
            )
        except CloudStackApiException:
            self.logger.debug("Router is deleted")

        except Exception as e:
            raise Exception(
                "Encountered %s raised while fetching routers for account: %s" %
                (e, self.account.name))
        return
