package com.example.ivor_hu.meizhi.services;

import android.app.IntentService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.android.volley.toolbox.RequestFuture;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;
import com.example.ivor_hu.meizhi.MainActivity;
import com.example.ivor_hu.meizhi.db.Image;
import com.example.ivor_hu.meizhi.net.ImageFetcher;
import com.example.ivor_hu.meizhi.utils.Constants;
import com.example.ivor_hu.meizhi.utils.DateUtil;
import com.example.ivor_hu.meizhi.utils.VolleyUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import io.realm.Realm;
import io.realm.RealmResults;

/**
 * Created by Ivor on 2016/2/12.
 */
public class ImageFetchService extends IntentService implements ImageFetcher {
    private static final String TAG = "ImageFetchService";
    public static final String ACTION_UPDATE_RESULT = "com.ivor.meizhi.girls_update_result";
    public static final String EXTRA_FETCHED = "girls_fetched";
    public static final String EXTRA_TRIGGER = "girls_trigger";
    public static final String ACTION_FETCH_REFRESH = "com.ivor.meizhi.girls_fetch_refresh";
    public static final String ACTION_FETCH_MORE = "com.ivor.meizhi.girls_fetch_more";
    private LocalBroadcastManager localBroadcastManager;

    public ImageFetchService() {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Realm realm = Realm.getDefaultInstance();

        RealmResults<Image> latest = Image.all(realm);

        int fetched = 0;
        try {
            if (latest.isEmpty()) {
                fetched = fetchLatest(realm);
                Log.d(TAG, "no latest, fresh fetch");
            } else if (ACTION_FETCH_REFRESH.equals(intent.getAction())) {
                Log.d(TAG, "latest fetch: " + latest.first().getPublishedAt());
                fetched = fetchRefresh(realm, latest.first().getPublishedAt());
            } else if (ACTION_FETCH_MORE.equals(intent.getAction())) {
                Log.d(TAG, "earliest fetch: " + latest.last().getPublishedAt());
                fetched = fetchMore(realm, latest.last().getPublishedAt());
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }

        sendResult(intent, realm, fetched);
    }

    private void sendResult(Intent intent, Realm realm, int fetched) {
        realm.close();

        Log.d(TAG, "finished fetching, actual fetched " + fetched);

        Intent broadcast = new Intent(ACTION_UPDATE_RESULT);
        broadcast.putExtra(EXTRA_FETCHED, fetched);
        broadcast.putExtra(EXTRA_TRIGGER, intent.getAction());

        localBroadcastManager.sendBroadcast(broadcast);
    }


    private int fetchLatest(final Realm realm) throws InterruptedException, ExecutionException, ParseException, JSONException {
        RequestFuture<JSONObject> future = VolleyUtil.getInstance(this).getJSONSync(MainActivity.TYPE.GIRLS.getLatestUrl(), MainActivity.TYPE.GIRLS.getId());
        return updateImages(realm, future);
    }

    private int fetchRefresh(Realm realm, Date publishedAt) throws InterruptedException, ExecutionException, ParseException, JSONException {
        String after = DateUtil.format(publishedAt);
        List<String> dates = DateUtil.generateSequenceDateTillToday(publishedAt);
        return fetch(realm, after, dates);
    }

    private int fetchMore(Realm realm, Date publishedAt) throws InterruptedException, ExecutionException, JSONException, ParseException {
        String before = DateUtil.format(publishedAt);
        List<String> dates = DateUtil.generateSequenceDateBefore(publishedAt, 20);
        return fetch(realm, before, dates);
    }

    private int fetch(Realm realm, String baseline, List<String> dates) throws InterruptedException, ExecutionException, JSONException, ParseException {
        int fetched = 0;
        for (String date : dates) {
            if (date == null)
                return fetched;

            if (date.equals(baseline))
                continue;

            RequestFuture<JSONObject> imgFuture = VolleyUtil.getInstance(this).getJSONSync(Constants.DAYLY_DATA_URL + date, MainActivity.TYPE.GIRLS.getId());
            JSONObject imgResponse = imgFuture.get();
            if (imgResponse.getBoolean("error"))
                continue;

            JSONObject results = imgResponse.getJSONObject("results");
            if (!results.has("福利"))
                continue;
            JSONObject img = results.getJSONArray("福利").getJSONObject(0);
            if (img == null)
                continue;

            if (!saveToDb(realm, new Image(img.getString("_id"), img.getString("url"), DateUtil.parse(img.getString("publishedAt"))))) {
                return fetched;
            }
            fetched++;
        }
        return fetched;
    }

    private int updateImages(Realm realm, RequestFuture<JSONObject> future) throws JSONException, ExecutionException, InterruptedException, ParseException {
        int fetched = 0;
        JSONObject response = future.get();
        if (response.getBoolean("error"))
            return 0;

        JSONArray array = response.getJSONArray("results");
        int len = array.length();
        String url, date, id;
        for (int i = 0; i < len; i++) {
            url = array.getJSONObject(i).getString("url");
            date = array.getJSONObject(i).getString("publishedAt");
            id = array.getJSONObject(i).getString("_id");
            if (!saveToDb(realm, new Image(id, url, DateUtil.parse(date)))) {
                return i;
            }
            fetched++;
        }

        return fetched;
    }

    private boolean saveToDb(Realm realm, Image image) {
        realm.beginTransaction();

        try {
            realm.copyToRealm(Image.persist(image, this));
            Log.d(TAG, "saveToDb: " + image.getPublishedAt());
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch image", e);
            realm.cancelTransaction();
            return false;
        }

        realm.commitTransaction();
        return true;
    }

    @Override
    public void prefetchImage(String url, Point measured) throws IOException, InterruptedException, ExecutionException {
        Bitmap bitmap = Glide.with(this)
                .load(url).asBitmap()
                .into(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .get();

        measured.x = bitmap.getWidth();
        measured.y = bitmap.getHeight();

//        Log.d(TAG, "pre-measured image: " + measured.x + " x " + measured.y + " " + url);
    }
}
