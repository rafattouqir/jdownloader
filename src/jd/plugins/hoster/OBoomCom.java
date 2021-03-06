package jd.plugins.hoster;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import jd.PluginWrapper;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.HexFormatter;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.os.CrossSystem;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "oboom.com" }, urls = { "https?://(www\\.)?oboom\\.com/(#(id=)?|#/)?[A-Z0-9]{8}" }, flags = { 2 })
public class OBoomCom extends PluginForHost {

    private static Map<Account, Map<String, String>> ACCOUNTINFOS = new HashMap<Account, Map<String, String>>();
    private final String                             APPID        = "43340D9C23";
    private final String                             REF_TOKEN    = "REF_TOKEN";

    public OBoomCom(PluginWrapper wrapper) {
        super(wrapper);
        enablePremium("https://www.oboom.com");
    }

    @Override
    public String getAGBLink() {
        return "https://www.oboom.com/#agb";
    }

    @Override
    public void correctDownloadLink(final DownloadLink link) {
        // clean links so prevent dupes and has less side effects with multihosters...
        // website redirects to domain/#fuid
        link.setUrlDownload(link.getDownloadURL().replaceAll("\\.com/#id=", "\\.com/#"));
        link.setUrlDownload(link.getDownloadURL().replaceAll("\\.com/#/", "\\.com/#"));
        final String linkID = getHost() + "://" + getFileID(link);
        try {
            link.setLinkID(linkID);
        } catch (Throwable e) {
            link.setProperty("LINKDUPEID", linkID);
        }
    }

    @Override
    public String getBuyPremiumUrl() {
        return "https://www.oboom.com/ref/C0ACB0?ref_token=" + getLatestRefID();
    }

    private String getLatestRefID() {
        String refID = "";
        try {
            final SubConfiguration pluginConfig = getPluginConfig();
            if (pluginConfig != null) {
                refID = pluginConfig.getStringProperty(REF_TOKEN, null);
                if (StringUtils.isEmpty(refID)) {
                    refID = "";
                }
            }
        } catch (final Throwable e) {
            e.printStackTrace();
        }
        return refID;
    }

    private void setLatestRefID(String ID) {
        final SubConfiguration pluginConfig = getPluginConfig();
        if (pluginConfig != null) {
            pluginConfig.setProperty(REF_TOKEN, ID);
        }
    }

    /**
     * defines custom browser requirements.
     * */
    private Browser prepBrowser(final Browser prepBr) {
        try {
            /* not available in old stable */
            prepBr.setAllowedResponseCodes(new int[] { 500 });
        } catch (Throwable e) {
        }
        // enable debug for older versions of JD
        if (System.getProperty("jd.revision.jdownloaderrevision") == null) {
            prepBr.setDebug(true);
        }
        return prepBr;
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        Map<String, String> infos = loginAPI(account, true);
        String max = infos.get("max");
        String current = infos.get("current");
        if (max != null && current != null) {
            long limit = Long.parseLong(max);
            long free = Long.parseLong(current);
            if (limit > 0) {
                ai.setTrafficMax(limit);
                ai.setTrafficLeft(Math.max(0, free));
            } else {
                ai.setUnlimitedTraffic();
            }
        } else {
            ai.setUnlimitedTraffic();
        }

        String premium = infos.get("premium_unix");
        if (premium != null) {
            long premiumUntil = Long.parseLong(premium) * 1000l;
            ai.setValidUntil(premiumUntil);
            try {
                // JD 2 CALL!
                ai.setValidPremiumUntil(premiumUntil);
            } catch (Throwable e) {
            }
            if (!ai.isExpired()) {
                ai.setStatus("Premium account");
                return ai;
            }
        }
        ai.setExpired(false);
        ai.setValidUntil(-1);
        ai.setStatus("Free Account");
        return ai;
    }

    private Map<String, String> loginAPI(Account account, boolean forceLogin) throws Exception {
        synchronized (ACCOUNTINFOS) {
            boolean follow = br.isFollowingRedirects();
            try {
                Map<String, String> infos = ACCOUNTINFOS.get(account);
                if (infos == null || forceLogin) {
                    prepBrowser(br);
                    br.setFollowRedirects(true);
                    if (account.getUser() == null || account.getUser().trim().length() == 0) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    if (account.getPass() == null || account.getPass().trim().length() == 0) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    String response = br.getPage("https://www.oboom.com/1.0/login?auth=" + Encoding.urlEncode(account.getUser()) + "&pass=" + PBKDF2Key(account.getPass()) + "&source=" + APPID);
                    if (br.containsHTML("400,\"Invalid Login") || !response.startsWith("[200")) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    infos = new HashMap<String, String>();
                    String keys[] = getKeys(response);
                    for (String key : keys) {
                        String value = getValue(response, key);
                        if (value != null) {
                            infos.put(key, value);
                        }
                    }
                    // infos.put("premium_unix", ("" + (System.currentTimeMillis() + 6 * 60 * 60 * 1000l) / 1000));
                    String premium_unix = infos.get("premium_unix");
                    if (premium_unix != null) {
                        long timeStamp = Long.parseLong(premium_unix) * 1000l;
                        account.setProperty("PREMIUM_UNIX", timeStamp);
                        if (timeStamp <= System.currentTimeMillis()) {
                            infos.remove("premium");
                        }
                    } else {
                        infos.remove("premium");
                    }
                    if (infos.get("premium") != null) {
                        account.setProperty("PREMIUM", true);
                        account.setConcurrentUsePossible(true);
                        account.setMaxSimultanDownloads(0);
                    } else {
                        account.setProperty("PREMIUM", Property.NULL);
                        account.setConcurrentUsePossible(false);
                        account.setMaxSimultanDownloads(1);
                    }
                    ACCOUNTINFOS.put(account, infos);
                }
                return infos;
            } catch (final Exception e) {
                account.setProperty("PREMIUM", Property.NULL);
                ACCOUNTINFOS.remove(account);
                throw e;
            } finally {
                br.setFollowRedirects(follow);
            }
        }
    }

    private String[] getKeys(String response) {
        return new Regex(response, "\"([a-zA-Z0-9\\_]+)\":").getColumn(0);
    }

    private String getValue(String response, String key) {
        String ret = new Regex(response, "\"" + key + "\":\\s*?\"(.*?)\"").getMatch(0);
        if (ret == null) {
            ret = new Regex(response, "\"" + key + "\":\\s*?([\\d\\-]+)").getMatch(0);
        }
        return ret;
    }

    private static String PBKDF2Key(String password) throws NoSuchAlgorithmException, InvalidKeySpecException, UnsupportedEncodingException {
        StringBuilder sb = new StringBuilder(password);
        byte[] salt = sb.reverse().toString().getBytes("UTF-8");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 1000, 16 * 8);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] hash = skf.generateSecret(spec).getEncoded();
        return HexFormatter.byteArrayToHex(hash);
    }

    private Map<String, String> getGuestSession(boolean forceNew, String forceNewIfSession, AtomicBoolean newSignal) throws Exception {
        synchronized (ACCOUNTINFOS) {
            boolean follow = br.isFollowingRedirects();
            try {
                Map<String, String> infos = ACCOUNTINFOS.get(null);
                if (infos == null || forceNew || forceNewIfSession != null && forceNewIfSession.equals(infos.get("guestSession"))) {
                    br.setFollowRedirects(true);
                    br.getPage("https://www.oboom.com/1.0/guestsession?source=" + APPID);
                    String guestSession = br.getRegex("200,.*?\"(.*?)\"").getMatch(0);
                    if (guestSession == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    infos = new HashMap<String, String>();
                    infos.put("guestSession", guestSession);
                    if (newSignal != null) {
                        newSignal.set(true);
                    }
                    ACCOUNTINFOS.put(null, infos);
                }
                if (newSignal != null) {
                    newSignal.set(false);
                }
                return infos;
            } catch (final Exception e) {
                ACCOUNTINFOS.remove(null);
                throw e;
            } finally {
                br.setFollowRedirects(follow);
            }
        }
    }

    @Override
    public boolean checkLinks(DownloadLink[] links) {
        if (links == null || links.length == 0) {
            return true;
        }
        try {
            final StringBuilder sb = new StringBuilder();
            final HashMap<String, DownloadLink> idLinks = new HashMap<String, DownloadLink>();
            for (DownloadLink link : links) {
                final String id = getFileID(link);
                idLinks.put(id, link);
                idLinks.put("lower_" + id.toLowerCase(Locale.ENGLISH), link);
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(id);
            }
            br.setReadTimeout(60 * 1000);
            br.getPage("https://api.oboom.com/1.0/info?items=" + sb.toString() + "&http_errors=0&with_ref_token=true");
            final String fileInfos[] = br.getRegex("\\{(.*?)\\}").getColumn(0);
            if (fileInfos != null) {
                for (String fileInfo : fileInfos) {
                    final String id = getValue(fileInfo, "id");
                    final String size = getValue(fileInfo, "size");
                    final String name = getValue(fileInfo, "name");
                    final String state = getValue(fileInfo, "state");
                    final String refToken = getValue(fileInfo, "ref_token");
                    DownloadLink link = idLinks.get(id);
                    if (link == null) {
                        link = idLinks.get("lower_" + id.toLowerCase(Locale.ENGLISH));
                    }
                    if (link == null) {
                        continue;
                    }
                    if (name != null) {
                        link.setFinalFileName(unescape(name));
                    }
                    try {
                        if (size != null) {
                            link.setDownloadSize(Long.parseLong(size));
                            link.setVerifiedFileSize(Long.parseLong(size));
                        }
                    } catch (final Throwable e) {
                    }
                    if ("online".equals(state)) {
                        setLatestRefID(refToken);
                        link.setAvailable(true);
                    } else {
                        link.setAvailable(false);
                    }
                }
                return fileInfos.length == links.length;
            }
            return false;
        } catch (final Throwable e) {
            LogSource.exception(logger, e);
            return false;
        }
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink parameter) throws Exception {
        return fetchFileInformation(parameter, null);
    }

    protected AvailableStatus fetchFileInformation(DownloadLink link, String session) throws Exception {
        prepBrowser(br);
        final String response;
        final String ID = getFileID(link);
        if (session != null) {
            response = br.getPage("https://api.oboom.com/1.0/info?token=" + session + "&items=" + ID + "&http_errors=0&with_ref_token=true");
        } else {
            response = br.getPage("https://api.oboom.com/1.0/info?items=" + ID + "&http_errors=0&with_ref_token=true");
        }

        if (response.contains("404,\"token") || response.contains("403,\"token")) {
            if (session != null) {
                return AvailableStatus.UNCHECKABLE;
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        final String size = getValue(response, "size");
        final String name = getValue(response, "name");
        final String state = getValue(response, "state");
        final String refToken = getValue(response, "ref_token");
        if (name != null) {
            link.setFinalFileName(unescape(name));
        }
        try {
            if (size != null) {
                link.setDownloadSize(Long.parseLong(size));
                link.setVerifiedFileSize(Long.parseLong(size));
            }
        } catch (final Throwable e) {
        }
        if (!"online".equals(state)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        setLatestRefID(refToken);
        return AvailableStatus.TRUE;
    }

    public String unescape(String s) {
        if (s == null) {
            return null;
        }
        char ch;
        char ch2;
        final StringBuilder sb = new StringBuilder();
        int ii;
        int i;
        for (i = 0; i < s.length(); i++) {
            ch = s.charAt(i);
            // prevents StringIndexOutOfBoundsException with ending char equals case trigger
            if (s.length() != i + 1) {
                switch (ch) {
                case '%':
                case '\\':
                    ch2 = ch;
                    ch = s.charAt(++i);
                    StringBuilder sb2 = null;
                    switch (ch) {
                    case 'u':
                        /* unicode */
                        sb2 = new StringBuilder();
                        i++;
                        ii = i + 4;
                        for (; i < ii; i++) {
                            ch = s.charAt(i);
                            if (sb2.length() > 0 || ch != '0') {
                                sb2.append(ch);
                            }
                        }
                        i--;
                        sb.append((char) Long.parseLong(sb2.toString(), 16));
                        continue;
                    case 'x':
                        /* normal hex coding */
                        sb2 = new StringBuilder();
                        i++;
                        ii = i + 2;
                        for (; i < ii; i++) {
                            ch = s.charAt(i);
                            sb2.append(ch);
                        }
                        i--;
                        sb.append((char) Long.parseLong(sb2.toString(), 16));
                        continue;
                    default:
                        if (ch2 == '%') {
                            sb.append(ch2);
                        }
                        sb.append(ch);
                        continue;
                    }
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        Map<String, String> usedInfos = null;
        boolean freshInfos = false;
        synchronized (ACCOUNTINFOS) {
            Map<String, String> currentInfos = ACCOUNTINFOS.get(account);
            usedInfos = loginAPI(account, false);
            freshInfos = currentInfos != usedInfos;
        }
        if (!usedInfos.containsKey("premium")) {
            handleFree(link, account);
            return;
        }
        if (AvailableStatus.UNCHECKABLE == fetchFileInformation(link, usedInfos.get("session"))) {
            refreshTokenHandling(usedInfos, account, freshInfos);
        }
        final String ID = getFileID(link);
        br.getPage("https://api.oboom.com/1.0/dl?token=" + usedInfos.get("session") + "&item=" + ID + "&http_errors=0");
        downloadErrorHandling(account);
        String urlInfos[] = br.getRegex("200,\"(.*?)\",\"(.*?)\"").getRow(0);
        if (urlInfos == null || urlInfos[0] == null || urlInfos[1] == null) {
            if (br.toString().length() > 200) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Error: " + br.toString());
            }
        }
        String url = "http://" + urlInfos[0] + "/1.0/dlh?ticket=" + urlInfos[1] + "&http_errors=0";
        br.setFollowRedirects(true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, url, true, 0);
        if (!dl.getConnection().isContentDisposition()) {
            if (dl.getConnection().getResponseCode() == 500) {
                dl.getConnection().disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
            }
            br.followConnection();
            downloadErrorHandling(account);
            refreshTokenHandling(usedInfos, account, freshInfos);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private void downloadErrorHandling(Account account) throws PluginException {
        if (br.containsHTML("403,\"(abused|blocked|deleted)")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.containsHTML("410,\"(abused|blocked|deleted)")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.containsHTML("403,\"permission")) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, PluginException.VALUE_ID_PREMIUM_ONLY);
        }
        if (br.containsHTML("500,\"internal")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Internal server error", 15 * 60 * 1000l);
        }
        if (br.containsHTML("503,\"download")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download currently unavailable", 15 * 60 * 1000l);
        }
        if (br.containsHTML("404,\"ticket")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download ticket invalid", 1 * 60 * 1000l);
        }
        if (br.containsHTML("509,\"bandwidth limit exceeded")) {
            if (account != null) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "Bandwidth limit exceeded", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Bandwidth limit exceeded", 30 * 60 * 1000l);
            }
        }
        String waitTime = br.getRegex("421,\"ip_blocked\",(\\d+)").getMatch(0);
        if (waitTime != null) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, Long.parseLong(waitTime) * 1000l);
        }
        if (account != null && br.getRegex("421,\"connections\",(\\d+)").getMatch(0) != null) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Already downloading?", 5 * 60 * 1000l);
        }
    }

    private void refreshTokenHandling(Map<String, String> usedInfos, Account account, final boolean freshInfos) throws PluginException {
        if ((br.containsHTML("403,\"token") || br.containsHTML("404,\"token") || br.containsHTML("403,\"resume") || br.containsHTML("421,\"connections")) && freshInfos == false) {
            /* only retry on NON-fresh tokens */
            synchronized (ACCOUNTINFOS) {
                if (ACCOUNTINFOS.get(account) == usedInfos) {
                    ACCOUNTINFOS.remove(account);
                }
            }
            throw new PluginException(LinkStatus.ERROR_RETRY);
        }
    }

    private String getFileID(DownloadLink link) {
        return new Regex(link.getDownloadURL(), "oboom\\.com/(#(id=)?|#/)?([A-Z0-9]{8})").getMatch(2);
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        handleFree(link, null);
    }

    private void checkShowFreeDialog() {
        SubConfiguration config = null;
        try {
            config = getPluginConfig();
            if (config.getBooleanProperty("premAdShown", Boolean.FALSE) == false) {
                if (config.getProperty("premAdShown2") == null) {
                    File checkFile = JDUtilities.getResourceFile("tmp/oboom");
                    if (!checkFile.exists()) {
                        checkFile.mkdirs();
                        showFreeDialog("oboom.com");
                    }
                } else {
                    config = null;
                }
            } else {
                config = null;
            }
        } catch (final Throwable e) {
        } finally {
            if (config != null) {
                config.setProperty("premAdShown", Boolean.TRUE);
                config.setProperty("premAdShown2", "shown");
                config.save();
            }
        }
    }

    protected void showFreeDialog(final String domain) {
        if (System.getProperty("org.jdownloader.revision") != null) { /* JD2 ONLY! */
            super.showFreeDialog(domain);
            return;
        }
        try {
            SwingUtilities.invokeAndWait(new Runnable() {

                @Override
                public void run() {
                    try {
                        String lng = System.getProperty("user.language");
                        String message = null;
                        String title = null;
                        String tab = "                        ";
                        if ("de".equalsIgnoreCase(lng)) {
                            title = domain + " Free Download";
                            message = "Du lädst im kostenlosen Modus von " + domain + ".\r\n";
                            message += "Wie bei allen anderen Hostern holt JDownloader auch hier das Beste für dich heraus!\r\n\r\n";
                            message += tab + "  Falls du allerdings mehrere Dateien\r\n" + "          - und das möglichst mit Fullspeed und ohne Unterbrechungen - \r\n" + "             laden willst, solltest du dir den Premium Modus anschauen.\r\n\r\nUnserer Erfahrung nach lohnt sich das - Aber entscheide am besten selbst. Jetzt ausprobieren?  ";
                        } else {
                            title = domain + " Free Download";
                            message = "You are using the " + domain + " Free Mode.\r\n";
                            message += "JDownloader always tries to get the best out of each hoster's free mode!\r\n\r\n";
                            message += tab + "   However, if you want to download multiple files\r\n" + tab + "- possibly at fullspeed and without any wait times - \r\n" + tab + "you really should have a look at the Premium Mode.\r\n\r\nIn our experience, Premium is well worth the money. Decide for yourself, though. Let's give it a try?   ";
                        }
                        if (CrossSystem.isOpenBrowserSupported()) {
                            int result = JOptionPane.showConfirmDialog(jd.gui.swing.jdgui.JDGui.getInstance().getMainFrame(), message, title, JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null);
                            if (JOptionPane.OK_OPTION == result) {
                                CrossSystem.openURL(new URL("http://update3.jdownloader.org/jdserv/BuyPremiumInterface/redirect?" + domain + "&freedialog"));
                            }
                        }
                    } catch (Throwable e) {
                    }
                }
            });
        } catch (Throwable e) {
        }
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, Account acc) {
        if (acc != null && acc.getMaxSimultanDownloads() == 0) {
            return false;
        }
        return true;
    }

    public void handleFree(DownloadLink link, Account account) throws Exception {
        AtomicBoolean freshInfos = new AtomicBoolean(false);
        final String ID = getFileID(link);
        Map<String, String> usedInfos = null;
        String session = null;
        if (account != null) {
            synchronized (ACCOUNTINFOS) {
                Map<String, String> currentInfos = ACCOUNTINFOS.get(account);
                usedInfos = loginAPI(account, false);
                session = usedInfos.get("session");
                freshInfos.set(currentInfos != usedInfos);
            }
        } else {
            usedInfos = getGuestSession(false, null, freshInfos);
            session = usedInfos.get("guestSession");
        }
        if (AvailableStatus.UNCHECKABLE == fetchFileInformation(link, session) && session != null) {
            refreshTokenHandling(usedInfos, account, freshInfos.get());
        }
        checkShowFreeDialog();
        if (session == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        try {
            br.setAllowedResponseCodes(421, 509);
        } catch (Throwable e) {
            // not in stable!
        }
        final PluginForHost recplug = JDUtilities.getPluginForHost("DirectHTTP");
        for (int i = 1; i <= 5; i++) {
            jd.plugins.hoster.DirectHTTP.Recaptcha rc = ((DirectHTTP) recplug).getReCaptcha(br);
            rc.setId("6LdqpO0SAAAAAJGHXo63HyalP7H4qlRs_vff0kJX");
            rc.load();
            File cf = rc.downloadCaptcha(getLocalCaptchaFile());
            String code = getCaptchaCode("recaptcha", cf, link);
            br.getPage("https://www.oboom.com/1.0/dl/ticket?token=" + session + "&download_id=" + ID + "&source=" + APPID + "&recaptcha_challenge_field=" + URLEncoder.encode(rc.getChallenge(), "UTF-8") + "&recaptcha_response_field=" + URLEncoder.encode(code, "UTF-8") + "&http_errors=0");
            if (br.containsHTML("incorrect-captcha-sol") || br.containsHTML("400,\"captcha-timeout")) {
                continue;
            }
            if (br.containsHTML("400,\"Forbidden")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Try again later.", 5 * 60 * 1000l);
            }
            break;
        }
        if (br.containsHTML("incorrect-captcha-sol") || br.containsHTML("400,\"captcha-timeout")) {
            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
        }
        if (br.containsHTML("400,\"slot_error\"")) {
            // country slot block. try again in 5 minutes
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Try again later.", 5 * 60 * 1000l);
        }
        String waitTime = br.getRegex("403,(\\-?\\d+)").getMatch(0);
        if (waitTime != null) {
            // there is already a download running.
            if (Integer.parseInt(waitTime) < 0) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 5 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, Long.parseLong(waitTime) * 1000l);
        }
        downloadErrorHandling(account);
        String urlInfos[] = br.getRegex("200,\"(.*?)\",\"(.*?)\"").getRow(0);
        if (urlInfos == null || urlInfos[0] == null || urlInfos[1] == null) {
            if (br.toString().length() > 200) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Error: " + br.toString());
            }

        }
        sleep(30 * 1000l, link);
        br.getPage("https://api.oboom.com/1.0/dl?token=" + urlInfos[0] + "&item=" + ID + "&auth=" + urlInfos[1] + "&http_errors=0");
        downloadErrorHandling(account);
        urlInfos = br.getRegex("200,\"(.*?)\",\"(.*?)\"").getRow(0);
        if (urlInfos == null || urlInfos[0] == null || urlInfos[1] == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String url = "http://" + urlInfos[0] + "/1.0/dlh?ticket=" + urlInfos[1] + "&http_errors=0";
        br.setFollowRedirects(true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, url, false, 1);

        if (!dl.getConnection().isContentDisposition()) {
            if (dl.getConnection().getResponseCode() == 500) {
                dl.getConnection().disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
            }
            br.followConnection();
            downloadErrorHandling(account);
            refreshTokenHandling(usedInfos, account, freshInfos.get());
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public boolean canHandle(DownloadLink downloadLink, Account account) {
        if (account != null && account.getMaxSimultanDownloads() == 0) {
            return true;
        }
        if (downloadLink.getVerifiedFileSize() >= 0) {
            return downloadLink.getVerifiedFileSize() < 1024 * 1024 * 1024l;
        }
        return true;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

}
