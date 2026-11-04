/**
 * Copyright 2017 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer.link;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.model.LinkTapEvent;

public class DefaultLinkHandler implements LinkHandler {

    private static final String TAG = DefaultLinkHandler.class.getSimpleName();

    private PDFView pdfView;

    public DefaultLinkHandler(PDFView pdfView) {
        this.pdfView = pdfView;
    }

    @Override
    public void handleLinkEvent(LinkTapEvent event) {
        String uri = event.getLink().getUri();
        Integer page = event.getLink().getDestPageIdx();
        if (uri != null && !uri.isEmpty()) {
            handleUri(uri);
        } else if (page != null) {
            handlePage(page);
        }
    }

    /**
     * Only the kinds of links a booklet has (a site, an email, a phone number). A link of any other kind in a
     * PDF (another app's private address, a file on the device, ...) is not followed: a PDF from anyone may
     * hold such links (security review, 2026-11-04).
     */
    static boolean allowed(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
            case "http": case "https": case "mailto": case "tel": return true;
            default: return false;
        }
    }

    private void handleUri(String uri) {
        Uri parsedUri = Uri.parse(uri);
        if (!allowed(parsedUri)) {
            Log.w(TAG, "Link not followed: " + parsedUri.getScheme());
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, parsedUri);
        // like a link in a web browser: only apps that say they accept links from the web may open it
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        Context context = pdfView.getContext();

        try {
            context.startActivity(intent);
        }
        catch (Exception e) {
            Log.w(TAG, "No app opens this link: " + e.getMessage());
        }
    }

    private void handlePage(int page) {
        pdfView.jumpTo(page);
    }
}
