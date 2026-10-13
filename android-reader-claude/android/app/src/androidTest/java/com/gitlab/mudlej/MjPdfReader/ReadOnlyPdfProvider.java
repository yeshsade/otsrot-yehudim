package com.gitlab.mudlej.MjPdfReader;

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

/** A share target that grants read access only, as WhatsApp does. Any write attempt is refused. Plain Java: runs in the test package's own process. */
public class ReadOnlyPdfProvider extends ContentProvider {
    public static final String NAME = "booklet-from-chat.pdf";
    public static final Uri URI = Uri.parse("content://com.yeshsade.otzarot.reader.test.readonly/media/" + NAME);

    @Override public boolean onCreate() { return true; }

    private File file() throws FileNotFoundException {
        File target = new File(getContext().getFilesDir(), NAME);
        if (!target.exists()) {
            try (InputStream in = getContext().getAssets().open("synthetic-checkbox-source.pdf");
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            } catch (IOException e) {
                throw new FileNotFoundException(e.toString());
            }
        }
        return target;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode.contains("w") || mode.contains("t") || mode.contains("a")) {
            throw new FileNotFoundException("read-only share");
        }
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            cursor.addRow(new Object[]{NAME, file().length()});
        } catch (FileNotFoundException ignored) {
        }
        return cursor;
    }

    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("read-only"); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new SecurityException("read-only"); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new SecurityException("read-only"); }
}
