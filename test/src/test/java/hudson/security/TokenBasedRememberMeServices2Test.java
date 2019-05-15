package hudson.security;

import com.gargoylesoftware.htmlunit.CookieManager;
import com.gargoylesoftware.htmlunit.util.Cookie;
import com.gargoylesoftware.htmlunit.xml.XmlPage;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import static java.util.logging.Level.FINEST;
import java.util.stream.Collectors;

import jenkins.model.Jenkins;
import jenkins.security.seed.UserSeedProperty;

import org.acegisecurity.Authentication;
import org.acegisecurity.AuthenticationException;
import org.acegisecurity.BadCredentialsException;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.GrantedAuthorityImpl;
import org.acegisecurity.ui.rememberme.TokenBasedRememberMeServices;
import org.acegisecurity.userdetails.UserDetails;
import org.acegisecurity.userdetails.UsernameNotFoundException;
import org.apache.commons.codec.binary.Base64;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.For;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.springframework.dao.DataAccessException;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.text.IsEmptyString.isEmptyString;
import static org.hamcrest.xml.HasXPath.hasXPath;

public class TokenBasedRememberMeServices2Test {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public LoggerRule logging = new LoggerRule();

    private static boolean failureInduced;

    @Before
    public void resetFailureInduced() {
        failureInduced = false;
    }

    @Test
    public void rememberMeAutoLoginFailure() throws Exception {
        j.jenkins.setSecurityRealm(new InvalidUserWhenLoggingBackInRealm());

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("alice", "alice", true);

        // we should see a remember me cookie
        Cookie c = getRememberMeCookie(wc);
        assertNotNull(c);

        // start a new session and attempt to access Jenkins,
        // which should cause autoLogin failures
        wc = j.createWebClient();
        wc.getCookieManager().addCookie(c);

        // even if SecurityRealm chokes, it shouldn't kill the page
        logging.capture(1000).record(TokenBasedRememberMeServices.class, FINEST);
        wc.goTo("");

        // make sure that the server recorded this failure
        assertTrue(failureInduced);
        assertTrue(logging.getMessages().stream().anyMatch(m -> m.contains("contained username 'alice' but was not found")));
        // and the problematic cookie should have been removed
        assertNull(getRememberMeCookie(wc));
    }

    private Cookie getRememberMeCookie(JenkinsRule.WebClient wc) {
        return wc.getCookieManager().getCookie(TokenBasedRememberMeServices2.ACEGI_SECURITY_HASHED_REMEMBER_ME_COOKIE_KEY);
    }

    private static class InvalidUserWhenLoggingBackInRealm extends AbstractPasswordBasedSecurityRealm {
        @Override
        protected UserDetails authenticate(String username, String password) throws AuthenticationException {
            if (username.equals(password)) {
                return new org.acegisecurity.userdetails.User(username, password, true, new GrantedAuthority[] {new GrantedAuthorityImpl("myteam")});
            }
            throw new BadCredentialsException(username);
        }

        @Override
        public GroupDetails loadGroupByGroupname(String groupname) throws UsernameNotFoundException, DataAccessException {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException, DataAccessException {
            failureInduced = true;
            throw new UsernameNotFoundException("intentionally not working");
        }
    }

    @Test
    public void basicFlow() throws Exception {
        j.jenkins.setSecurityRealm(new StupidRealm());

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("bob", "bob", true);

        // we should see a remember me cookie
        Cookie c = getRememberMeCookie(wc);
        assertNotNull(c);

        // start a new session and attempt to access Jenkins,
        wc = j.createWebClient();
        wc.getCookieManager().addCookie(c);

        // this will trigger remember me
        wc.goTo("");

        // make sure that our security realm failed to report the info correctly
        assertTrue(failureInduced);
        // but we should have logged in
        wc.executeOnServer(() -> {
            Authentication a = Jenkins.getAuthentication();
            assertEquals("bob", a.getName());
            assertEquals(ImmutableList.of("authenticated", "myteam"), Arrays.asList(a.getAuthorities()).stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList()));
            return null;
        });
    }

    private static class StupidRealm extends InvalidUserWhenLoggingBackInRealm {
        @Override
        public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException, DataAccessException {
            failureInduced = true;
            throw new UserMayOrMayNotExistException("I cannot tell");
        }
    }

    @Test
    @Issue("SECURITY-868")
    @For(UserSeedProperty.class)
    public void rememberMeToken_invalid_afterUserSeedReset() throws Exception {
        j.jenkins.setDisableRememberMe(false);

        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        j.jenkins.setSecurityRealm(realm);

        String username = "alice";
        hudson.model.User alice = realm.createAccount(username, username);

        JenkinsRule.WebClient wc = j.createWebClient();

        wc.login(username, username, true);
        CookieManager cm = wc.getCookieManager();

        cm.removeCookie(cm.getCookie("JSESSIONID"));
        assertUserConnected(wc, username);

        alice.getProperty(UserSeedProperty.class).renewSeed();

        cm.removeCookie(cm.getCookie("JSESSIONID"));
        assertUserNotConnected(wc, username);
    }

    @Test
    @Issue("SECURITY-868")
    @For(UserSeedProperty.class)
    public void rememberMeToken_stillValid_afterUserSeedReset_ifUserSeedDisabled() throws Exception {
        boolean currentStatus = UserSeedProperty.DISABLE_USER_SEED;
        try {
            UserSeedProperty.DISABLE_USER_SEED = true;

            j.jenkins.setDisableRememberMe(false);

            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            j.jenkins.setSecurityRealm(realm);

            String username = "alice";
            hudson.model.User alice = realm.createAccount(username, username);

            JenkinsRule.WebClient wc = j.createWebClient();

            wc.login(username, username, true);
            CookieManager cm = wc.getCookieManager();

            cm.removeCookie(cm.getCookie("JSESSIONID"));
            assertUserConnected(wc, username);

            alice.getProperty(UserSeedProperty.class).renewSeed();

            cm.removeCookie(cm.getCookie("JSESSIONID"));
            // as userSeed disabled, no care about the renew
            assertUserConnected(wc, username);
        } finally {
            UserSeedProperty.DISABLE_USER_SEED = currentStatus;
        }
    }

    @Test
    @Issue("SECURITY-868")
    public void rememberMeToken_shouldNotAccept_expirationDurationLargerThanConfigured() throws Exception {
        j.jenkins.setDisableRememberMe(false);

        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        TokenBasedRememberMeServices2 tokenService = (TokenBasedRememberMeServices2) realm.getSecurityComponents().rememberMe;
        j.jenkins.setSecurityRealm(realm);

        String username = "alice";
        hudson.model.User alice = realm.createAccount(username, username);

        { // a malicious cookie with expiration too far in the future should not work
            JenkinsRule.WebClient wc = j.createWebClient();

            // by default we have 14 days of validity,
            // here we increase artificially the duration of validity, that could be used to have permanent access
            long oneDay = TimeUnit.DAYS.toMillis(1);
            Cookie cookie = createRememberMeCookie(tokenService, oneDay, alice);
            wc.getCookieManager().addCookie(cookie);

            // the application should not use the cookie to connect
            assertUserNotConnected(wc, username);
        }

        { // a hand crafted cookie with regular expiration duration works
            JenkinsRule.WebClient wc = j.createWebClient();

            // by default we have 14 days of validity,
            // here we reduce a bit the expiration date to simulate an "old" cookie (regular usage)
            long minusFiveMinutes = TimeUnit.MINUTES.toMillis(-5);
            Cookie cookie = createRememberMeCookie(tokenService, minusFiveMinutes, alice);
            wc.getCookieManager().addCookie(cookie);

            // if we reactivate the remember me feature, it's ok
            assertUserConnected(wc, username);
        }
    }

    @Test
    @Issue("SECURITY-868")
    public void rememberMeToken_skipExpirationCheck() throws Exception {
        boolean previousConfig = TokenBasedRememberMeServices2.SKIP_TOO_FAR_EXPIRATION_DATE_CHECK;
        try {
            TokenBasedRememberMeServices2.SKIP_TOO_FAR_EXPIRATION_DATE_CHECK = true;

            j.jenkins.setDisableRememberMe(false);

            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            TokenBasedRememberMeServices2 tokenService = (TokenBasedRememberMeServices2) realm.getSecurityComponents().rememberMe;
            j.jenkins.setSecurityRealm(realm);

            String username = "alice";
            hudson.model.User alice = realm.createAccount(username, username);

            { // a malicious cookie with expiration too far in the future should not work
                JenkinsRule.WebClient wc = j.createWebClient();

                // by default we have 14 days of validity,
                // here we increase artificially the duration of validity, that could be used to have permanent access
                long oneDay = TimeUnit.DAYS.toMillis(1);
                Cookie cookie = createRememberMeCookie(tokenService, oneDay, alice);
                wc.getCookieManager().addCookie(cookie);

                // the application should not use the cookie to connect
                assertUserConnected(wc, username);
            }

            { // a hand crafted cookie with regular expiration duration works
                JenkinsRule.WebClient wc = j.createWebClient();

                // by default we have 14 days of validity,
                // here we reduce a bit the expiration date to simulate an "old" cookie (regular usage)
                long minusFiveMinutes = TimeUnit.MINUTES.toMillis(-5);
                Cookie cookie = createRememberMeCookie(tokenService, minusFiveMinutes, alice);
                wc.getCookieManager().addCookie(cookie);

                // if we reactivate the remember me feature, it's ok
                assertUserConnected(wc, username);
            }
        } finally {
            TokenBasedRememberMeServices2.SKIP_TOO_FAR_EXPIRATION_DATE_CHECK = previousConfig;
        }
    }

    private Cookie createRememberMeCookie(TokenBasedRememberMeServices2 tokenService, long deltaDuration, hudson.model.User user) throws Exception {
        long tokenValiditySeconds = tokenService.getTokenValiditySeconds();
        long expiryTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(tokenValiditySeconds);

        // the hack
        expiryTime += deltaDuration;

        String signatureValue = tokenService.makeTokenSignature(expiryTime, user.getProperty(HudsonPrivateSecurityRealm.Details.class));
        String tokenValue = user.getId() + ":" + expiryTime + ":" + signatureValue;
        String tokenValueBase64 = new String(Base64.encodeBase64(tokenValue.getBytes()));
        return new Cookie(j.getURL().getHost(), tokenService.getCookieName(), tokenValueBase64);
    }

    private void assertUserConnected(JenkinsRule.WebClient wc, String expectedUsername) throws Exception {
        XmlPage page = (XmlPage) wc.goTo("whoAmI/api/xml", "application/xml");
        assertThat(page, hasXPath("//name", is(expectedUsername)));
    }

    private void assertUserNotConnected(JenkinsRule.WebClient wc, String notExpectedUsername) throws Exception {
        XmlPage page = (XmlPage) wc.goTo("whoAmI/api/xml", "application/xml");
        assertThat(page, hasXPath("//name", not(is(notExpectedUsername))));
    }

    @Test
    @Issue("SECURITY-996")
    public void rememberMeToken_shouldNotBeRead_ifOptionIsDisabled() throws Exception {
        j.jenkins.setDisableRememberMe(false);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        Cookie rememberMeCookie = null;
        {
            JenkinsRule.WebClient wc = j.createWebClient();
            wc.login("alice", "alice", true);


            // we should see a remember me cookie
            rememberMeCookie = getRememberMeCookie(wc);
            assertNotNull(rememberMeCookie);
            assertThat(rememberMeCookie.getValue(), not(isEmptyString()));
        }

        j.jenkins.setDisableRememberMe(true);
        {
            JenkinsRule.WebClient wc = j.createWebClient();

            wc.getCookieManager().addCookie(rememberMeCookie);

            // the application should not use the cookie to connect
            XmlPage page = (XmlPage) wc.goTo("whoAmI/api/xml", "application/xml");
            assertThat(page, hasXPath("//name", not(is("alice"))));
        }

        j.jenkins.setDisableRememberMe(false);
        {
            JenkinsRule.WebClient wc = j.createWebClient();

            wc.getCookieManager().addCookie(rememberMeCookie);

            // if we reactivate the remember me feature, it's ok
            XmlPage page = (XmlPage) wc.goTo("whoAmI/api/xml", "application/xml");
            assertThat(page, hasXPath("//name", is("alice")));
        }
    }
}
