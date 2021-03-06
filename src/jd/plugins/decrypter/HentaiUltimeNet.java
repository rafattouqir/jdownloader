//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
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

package jd.plugins.decrypter;

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser.BrowserException;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "hentai-ultime.net" }, urls = { "http://(www\\.)?gallery\\.hentai\\-ultime\\.net/v/((?!Artistes)[^<>\"]+/[^<>\"]+/|Artistes/[^<>\"]+/[^<>\"]+/)" }, flags = { 0 })
public class HentaiUltimeNet extends PluginForDecrypt {

    public HentaiUltimeNet(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        try {
            br.getPage(parameter);
        } catch (final BrowserException e) {
            final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
            offline.setAvailable(false);
            offline.setProperty("offline", true);
            decryptedLinks.add(offline);
            return decryptedLinks;
        }

        if (br.containsHTML("Item not found")) {
            logger.info("Link offline (received error 404): " + parameter);
            final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
            offline.setAvailable(false);
            offline.setProperty("offline", true);
            decryptedLinks.add(offline);
            return decryptedLinks;
        }

        final String fpName = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
        final String[] links = br.getRegex("</script>[\t\n\r ]+<a href=\"(/v/[^<>\"]*?)\"").getColumn(0);
        if (links == null || links.length == 0) {
            logger.warning("Decrypter broken for link: " + parameter);
            return null;
        }
        for (String singleLink : links) {
            singleLink = "http://gallery.hentai-ultime.net" + singleLink;
            // Check if link is a picture or another gallery
            if (!singleLink.endsWith(".jpg.html")) {
                decryptedLinks.add(createDownloadlink(singleLink));
            } else {
                br.getPage(singleLink);
                final String finallink = br.getRegex("<div id=\"gsImageView\">[\t\n\r ]+<img src=\"(/[^<>\"]*?)\"").getMatch(0);
                if (finallink == null) {
                    logger.warning("Decrypter broken for link: " + parameter);
                    return null;
                }
                final DownloadLink dl = createDownloadlink("directhttp://http://gallery.hentai-ultime.net" + finallink);
                dl.setAvailable(true);
                decryptedLinks.add(dl);
            }
        }
        if (fpName != null) {
            FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(decryptedLinks);
        }
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}