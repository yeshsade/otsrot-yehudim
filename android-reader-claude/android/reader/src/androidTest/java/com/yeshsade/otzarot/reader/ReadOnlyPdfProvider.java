package com.yeshsade.otzarot.reader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A share target that gives read access only, as WhatsApp does. Writes are refused.
 * "booklet-1.pdf" is the test booklet; "booklet-2.pdf" is a corrected edition (different bytes).
 * Plain Java: it runs in the test package's own process, which has no Kotlin runtime.
 */
public class ReadOnlyPdfProvider extends ContentProvider {
    public static final String AUTHORITY = "com.yeshsade.otzarot.reader.test.readonly";

    public static Uri uri(String name) {
        return Uri.parse("content://" + AUTHORITY + "/media/" + name);
    }

    @Override public boolean onCreate() { return true; }

    private File file(String name) throws FileNotFoundException {
        File target = new File(getContext().getFilesDir(), name);
        if (!target.exists()) {
            // "map-…" is the booklet with a hidden progress map (stage 2); everything else is the plain test booklet
            String asset = name.startsWith("map-") ? "map-booklet.pdf" : "synthetic-checkbox-source.pdf";
            try (InputStream in = getContext().getAssets().open(asset);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                if (name.contains("2")) out.write("\n% corrected edition\n".getBytes());
            } catch (IOException e) {
                throw new FileNotFoundException(e.toString());
            }
        }
        return target;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode.contains("w") || mode.contains("t") || mode.contains("a")) throw new FileNotFoundException("read-only share");
        return ParcelFileDescriptor.open(file(uri.getLastPathSegment()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            cursor.addRow(new Object[]{uri.getLastPathSegment(), file(uri.getLastPathSegment()).length()});
        } catch (FileNotFoundException ignored) {
        }
        return cursor;
    }

    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("read-only"); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new SecurityException("read-only"); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new SecurityException("read-only"); }
}
