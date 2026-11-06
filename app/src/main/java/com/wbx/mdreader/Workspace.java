package com.wbx.mdreader;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/** 通过 SAF 访问一个文件夹工作区（无需任何存储权限）。 */
final class Workspace {

    static final class Doc {
        final String id;
        final String name;
        final boolean dir;
        final long modified;

        Doc(String id, String name, boolean dir, long modified) {
            this.id = id;
            this.name = name;
            this.dir = dir;
            this.modified = modified;
        }
    }

    private static final String PREF_KEY = "workspace_uri";

    final Uri treeUri;
    final String rootId;
    final String title;

    private Workspace(Uri treeUri, String rootId, String title) {
        this.treeUri = treeUri;
        this.rootId = rootId;
        this.title = title;
    }

    static Workspace open(Context ctx, Uri treeUri) {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        String title = "工作区";
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            Cursor c = ctx.getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    title = c.getString(0);
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        Workspace ws = new Workspace(treeUri, rootId, title);
        SharedPreferences.Editor ed = prefs(ctx).edit();
        ed.putString(PREF_KEY, treeUri.toString());
        ed.apply();
        return ws;
    }

    static Workspace load(Context ctx) {
        String raw = prefs(ctx).getString(PREF_KEY, null);
        if (raw == null || raw.length() == 0) {
            return null;
        }
        try {
            Uri uri = Uri.parse(raw);
            String rootId = DocumentsContract.getTreeDocumentId(uri);
            String title = "工作区";
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(uri, rootId);
            Cursor c = ctx.getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    title = c.getString(0);
                }
                c.close();
            }
            return new Workspace(uri, rootId, title);
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().remove(PREF_KEY).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("md_reader", Context.MODE_PRIVATE);
    }

    Uri uriFor(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    Uri childListUri(String parentDocId) {
        return DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
    }

    ArrayList<Doc> list(ContentResolver cr, String parentDocId) {
        ArrayList<Doc> out = new ArrayList<Doc>();
        Cursor c = null;
        try {
            c = cr.query(childListUri(parentDocId), new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long mod = c.getLong(3);
                    boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    if (name == null) {
                        continue;
                    }
                    if (name.startsWith(".")) {
                        continue;
                    }
                    if (!dir && !isMarkdown(name)) {
                        continue;
                    }
                    out.add(new Doc(id, name, dir, mod));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<Doc>() {
            @Override
            public int compare(Doc a, Doc b) {
                if (a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                return collator.compare(a.name, b.name);
            }
        });
        return out;
    }

    static boolean isMarkdown(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".mdown") || n.endsWith(".txt");
    }

    Doc create(ContentResolver cr, String parentDocId, String name) throws Exception {
        Uri parent = uriFor(parentDocId);
        Uri created = DocumentsContract.createDocument(cr, parent, "text/markdown", name);
        if (created == null) {
            created = DocumentsContract.createDocument(cr, parent, "text/plain", name);
        }
        if (created == null) {
            throw new IOException("create failed");
        }
        String newId = DocumentsContract.getDocumentId(created);
        return new Doc(newId, name, false, System.currentTimeMillis());
    }

    void rename(ContentResolver cr, Doc doc, String newName) throws Exception {
        DocumentsContract.renameDocument(cr, uriFor(doc.id), newName);
    }

    void delete(ContentResolver cr, Doc doc) throws Exception {
        DocumentsContract.deleteDocument(cr, uriFor(doc.id));
    }

    // ---------------- 读写 ----------------

    static String readText(ContentResolver cr, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) {
                throw new IOException("no path");
            }
            return decode(readAll(new FileInputStream(new File(path))));
        }
        InputStream in = cr.openInputStream(uri);
        if (in == null) {
            throw new IOException("no stream");
        }
        return decode(readAll(in));
    }

    static void writeText(ContentResolver cr, Uri uri, String text) throws IOException {
        byte[] data = text.getBytes(Charset.forName("UTF-8"));
        OutputStream out = null;
        try {
            out = cr.openOutputStream(uri, "wt");
        } catch (Exception e) {
            out = null;
        }
        if (out == null) {
            out = cr.openOutputStream(uri, "w");
        }
        if (out == null) {
            throw new IOException("no stream");
        }
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 16 * 1024 * 1024) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, Charset.forName("UTF-8"));
        }
        String utf8 = new String(bytes, Charset.forName("UTF-8"));
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                String gbk = new String(bytes, Charset.forName("GBK"));
                if (gbk.indexOf('\uFFFD') < 0) {
                    return gbk;
                }
            } catch (Exception ignored) {
            }
        }
        return utf8;
    }

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
