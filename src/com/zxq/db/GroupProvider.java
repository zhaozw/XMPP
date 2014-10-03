package com.zxq.db;

import android.content.*;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;
import com.zxq.util.LogUtil;

import java.util.ArrayList;

public class GroupProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zxq.xx.provider.Groups";
    public static final String TABLE_NAME = "groups";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/" + TABLE_NAME);

    private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int MESSAGES = 1;
    private static final int MESSAGE_ID = 2;

    static {
        URI_MATCHER.addURI(AUTHORITY, "groups", MESSAGES);
        URI_MATCHER.addURI(AUTHORITY, "groups/#", MESSAGE_ID);
    }

    private static final String TAG = "GroupProvider";

    private SQLiteOpenHelper mOpenHelper;

    public GroupProvider() {
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        switch (URI_MATCHER.match(url)) {

            case MESSAGES:
                count = db.delete(TABLE_NAME, where, whereArgs);
                break;
            case MESSAGE_ID:
                String segment = url.getPathSegments().get(1);

                if (TextUtils.isEmpty(where)) {
                    where = "_id=" + segment;
                } else {
                    where = "_id=" + segment + " AND (" + where + ")";
                }

                count = db.delete(TABLE_NAME, where, whereArgs);
                break;
            default:
                throw new IllegalArgumentException("Cannot delete from URL: " + url);
        }

        getContext().getContentResolver().notifyChange(url, null);
        return count;
    }

    @Override
    public String getType(Uri url) {
        int match = URI_MATCHER.match(url);
        switch (match) {
            case MESSAGES:
                return GroupConstants.CONTENT_TYPE;
            case MESSAGE_ID:
                return GroupConstants.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URL");
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        if (URI_MATCHER.match(url) != MESSAGES) {
            throw new IllegalArgumentException("Cannot insert into URL: " + url);
        }

        ContentValues values = (initialValues != null) ? new ContentValues(initialValues) : new ContentValues();

        for (String colName : GroupConstants.getRequiredColumns()) {
            if (values.containsKey(colName) == false) {
                throw new IllegalArgumentException("Missing column: " + colName);
            }
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        long rowId = db.insert(TABLE_NAME, null, values);

        if (rowId < 0) {
            throw new SQLException("Failed to insert row into " + url);
        }

        Uri noteUri = ContentUris.withAppendedId(CONTENT_URI, rowId);
        getContext().getContentResolver().notifyChange(noteUri, null);
        return noteUri;
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new GroupDatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection, String[] selectionArgs, String sortOrder) {

        SQLiteQueryBuilder qBuilder = new SQLiteQueryBuilder();
        int match = URI_MATCHER.match(url);

        switch (match) {
            case MESSAGES:
                qBuilder.setTables(TABLE_NAME);
                break;
            case MESSAGE_ID:
                qBuilder.setTables(TABLE_NAME);
                qBuilder.appendWhere("_id=");
                qBuilder.appendWhere(url.getPathSegments().get(1));
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }

        String orderBy;
        if (TextUtils.isEmpty(sortOrder)) {
            orderBy = GroupConstants.DEFAULT_SORT_ORDER;
        } else {
            orderBy = sortOrder;
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qBuilder.query(db, projectionIn, selection, selectionArgs, null, null, orderBy);

        if (ret == null) {
            infoLog("ChatProvider.query: failed");
        } else {
            ret.setNotificationUri(getContext().getContentResolver(), url);
        }

        return ret;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        int count;
        long rowId = 0;
        int match = URI_MATCHER.match(url);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        switch (match) {
            case MESSAGES:
                count = db.update(TABLE_NAME, values, where, whereArgs);
                break;
            case MESSAGE_ID:
                String segment = url.getPathSegments().get(1);
                rowId = Long.parseLong(segment);
                count = db.update(TABLE_NAME, values, "_id=" + rowId, null);
                break;
            default:
                throw new UnsupportedOperationException("Cannot update URL: " + url);
        }

        infoLog("*** notifyChange() rowId: " + rowId + " url " + url);

        getContext().getContentResolver().notifyChange(url, null);
        return count;

    }

    private static void infoLog(String data) {
        LogUtil.i(TAG, data);
    }

    private static class GroupDatabaseHelper extends SQLiteOpenHelper {

        private static final String DATABASE_NAME = "chat.db";
        private static final int DATABASE_VERSION = 6;

        public GroupDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            infoLog("creating new chat table");

            db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                    + GroupConstants._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + GroupConstants.GROUP_ID + " VARCHAR(255),"
                    + GroupConstants.GROUP_CREATE_TIME + "  VARCHAR(255),"
                    + GroupConstants.GROUP_ALTER_TIME + "  VARCHAR(255),"
                    + GroupConstants.GROUP_SERVICE + "  VARCHAR(255),"
                    + GroupConstants.GROUP_NAME + "  VARCHAR(255),"
                    + GroupConstants.GROUP_DESCRIPTION + "  VARCHAR(255),"
                    + GroupConstants.GROUP_TITLE + "  VARCHAR(255),"
                    + GroupConstants.GROUP_MAX_USER + "  INTEGER);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            infoLog("onUpgrade: from " + oldVersion + " to " + newVersion);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }
    }


    public static final class GroupConstants implements BaseColumns {

        private GroupConstants() {
        }

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.yaxim.chat";
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.yaxim.chat";
        public static final String DEFAULT_SORT_ORDER = "_id ASC"; // sort by
        // auto-id


        public static final String GROUP_ID = "groupId";
        public static final String GROUP_CREATE_TIME = "createTime";
        public static final String GROUP_ALTER_TIME = "alterTime";
        public static final String GROUP_SERVICE = "service";
        public static final String GROUP_NAME = "name";
        public static final String GROUP_DESCRIPTION = "description";
        public static final String GROUP_TITLE = "title";
        public static final String GROUP_MAX_USER = "maxUser";


        public static ArrayList<String> getRequiredColumns() {
            ArrayList<String> tmpList = new ArrayList<String>();
            tmpList.add(GROUP_ID);
            tmpList.add(GROUP_CREATE_TIME);
            tmpList.add(GROUP_ALTER_TIME);
            tmpList.add(GROUP_SERVICE);
            tmpList.add(GROUP_NAME);
            tmpList.add(GROUP_DESCRIPTION);
            tmpList.add(GROUP_TITLE);
            tmpList.add(GROUP_MAX_USER);
            return tmpList;
        }

    }


}