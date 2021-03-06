//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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
import jd.http.Browser.BrowserException;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.utils.formatter.SizeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "400disk.com" }, urls = { "http://(www\\.)?400disk\\.com/(file|rf)view_\\d+\\.html" }, flags = { 0 })
public class FourHundredDiskCom extends PluginForHost {

    public FourHundredDiskCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.400disk.com/";
    }

    public void correctDownloadLink(DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("rfview_", "fileview_"));
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getHeaders().put("User-Agent", jd.plugins.hoster.MediafireCom.stringUserAgent());
        try {
            br.getPage(link.getDownloadURL());
        } catch (final BrowserException e) {
            if (br.getHttpConnection().getResponseCode() == 500) throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            throw e;
        }
        if (br.getURL().equals("http://www.400disk.com/error.php") || br.containsHTML("class=\\'alert_error\\'")) throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        String filename = br.getRegex("<h1><img src=\\'[^<>\"]*?\\'>([^<>\"]*?)</h1>").getMatch(0);
        if (filename == null) filename = br.getRegex("class=\"nowrap file\\-name [A-Za-z0-9\\-_]+\">([^<>]*?)</h1>").getMatch(0);
        String filesize = br.getRegex("<b>文件大小 ：</b>([^<>\"]*?)</li>").getMatch(0);
        if (filesize == null) filesize = br.getRegex("\">文件大小：([^<>\"]*?)</td>").getMatch(0);
        if (filename == null || filesize == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        link.setFinalFileName(Encoding.htmlDecode(filename.trim()));
        link.setDownloadSize(SizeFormatter.getSize(filesize + "b"));
        String md5 = br.getRegex(">M D 5值 ：</b>([a-f0-9]{32})</li>").getMatch(0);
        if (md5 == null) md5 = br.getRegex("文件MD5：([a-f0-9]{32})</td>").getMatch(0);
        if (md5 != null) link.setMD5Hash(md5);
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        final String fid = new Regex(downloadLink.getDownloadURL(), "(\\d+)\\.html$").getMatch(0);
        // http://www.400disk.com/?ac=download&id=137241&share=1&type=dx&t=1399241475671
        br.getPage("http://www.400disk.com/?ac=download&id=" + fid + "&share=1&type=dx&t=" + System.currentTimeMillis());
        final String dllink = br.getRegex("\"(http://[a-z0-9]+\\.400disk\\.com/file/[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, Encoding.htmlDecode(dllink), false, 1);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            if (br.containsHTML("<title>Error</title>|<TITLE>无法找到该页</TITLE>")) throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error");
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