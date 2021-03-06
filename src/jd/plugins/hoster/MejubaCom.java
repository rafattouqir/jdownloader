//jDownloader - Downloadmanager
//Copyright (C) 2012  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.IOException;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "mejuba.com" }, urls = { "http://(www\\.)?mejuba\\.com/(albums/[a-z0-9_\\-]+/\\d+/\\d+/\\d+|embed/(\\d+/\\d+[a-z0-9_\\-]+))" }, flags = { 0 })
public class MejubaCom extends PluginForHost {

    public MejubaCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.mejuba.com/terms/loggedin";
    }

    private static final String NORMAL_LINK   = "http://(www\\.)?mejuba\\.com/albums/[a-z0-9_\\-]+/\\d+/\\d+/\\d+";
    private static final String EMBEDDED_LINK = "http://(www\\.)?mejuba\\.com/embed/\\d+/\\d+";
    private String              DLLINK        = null;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(downloadLink.getDownloadURL());
        String filename = null;
        if (downloadLink.getDownloadURL().matches(NORMAL_LINK)) {
            filename = new Regex(downloadLink.getDownloadURL(), "mejuba\\.com/albums/[a-z0-9_\\-]+/\\d+/(\\d+)/").getMatch(0);
        } else {
            filename = new Regex(downloadLink.getDownloadURL(), "([a-z0-9_\\-]+)$").getMatch(0);
        }
        DLLINK = br.getRegex("url: \\'(http://[^<>\"]*?)\\'").getMatch(0);
        if (DLLINK == null) DLLINK = br.getRegex("\"(http://\\d+\\.\\d+\\.\\d+\\.\\d+/f/[^<>\"]*?)\"").getMatch(0);
        if (DLLINK == null) DLLINK = br.getRegex("(http://\\d+\\.\\d+\\.\\d+\\.\\d+/f/[^<>\"]*?)(\"|\\')").getMatch(0);
        if (filename == null || DLLINK == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        DLLINK = Encoding.htmlDecode(DLLINK);
        filename = filename.trim();
        String ext = DLLINK.substring(DLLINK.lastIndexOf("."));
        if (ext == null || ext.length() > 5) ext = ".flv";
        downloadLink.setFinalFileName(Encoding.htmlDecode(filename) + ext);
        final Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openGetConnection(DLLINK);
            if (!con.getContentType().contains("html"))
                downloadLink.setDownloadSize(con.getLongContentLength());
            else
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            return AvailableStatus.TRUE;
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, DLLINK, true, 0);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

}