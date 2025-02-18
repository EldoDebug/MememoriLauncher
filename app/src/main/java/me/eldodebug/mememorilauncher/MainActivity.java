package me.eldodebug.mememorilauncher;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.eldodebug.mememorilauncher.util.MememoriUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MememoriLauncher";
    private static final String VERSION_CHECK_URL = "https://mememori-game.com/apps/vars.js";
    private static final String BASE_DOWNLOAD_URL = "https://mememori-game.com/apps/";

    private TextView statusText;
    private ProgressBar progressBar;
    private ExecutorService executorService;
    private String latestVersion;
    private ActivityResultLauncher<Intent> installPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.GONE);
        executorService = Executors.newSingleThreadExecutor();

        setupInstallPermissionLauncher();
        checkAndRequestPermissions();
    }

    private void setupInstallPermissionLauncher() {
        installPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "インストール権限の結果");
                    if (getPackageManager().canRequestPackageInstalls()) {
                        startVersionCheck();
                    } else {
                        Toast.makeText(this, "インストール権限が必要です", Toast.LENGTH_LONG).show();
                        finish();
                    }
                }
        );
    }

    private void checkAndRequestPermissions() {
        Log.d(TAG, "権限チェック開始");
        boolean shouldStartVersionCheck = true;

        if (!getPackageManager().canRequestPackageInstalls()) {
            Log.d(TAG, "インストール権限を要求");
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + getPackageName()));
            installPermissionLauncher.launch(intent);
            shouldStartVersionCheck = false;
        }

        if (shouldStartVersionCheck) {
            Log.d(TAG, "すべての権限が許可されています");
            startVersionCheck();
        } else {
            Log.d(TAG, "権限の取得待ち");
            statusText.setText("インストール権限の設定をしてください");
        }
    }

    private void startVersionCheck() {
        Log.d(TAG, "バージョンチェック開始");
        statusText.setText("バージョンを確認中...");

        executorService.execute(() -> {
            try {
                String versionInfo = fetchVersionInfo();
                Log.d(TAG, "取得したバージョン情報: " + versionInfo);

                if (versionInfo != null) {
                    latestVersion = extractVersion(versionInfo);
                    Log.d(TAG, "抽出したバージョン: " + latestVersion);

                    runOnUiThread(() -> {

                        // メメントモリがインストールされている場合の処理
                        if(MememoriUtil.hasMememori(this)) {

                            String currentVersion = MememoriUtil.getVersionName(this);

                            // メメントモリが最新バージョンか確認する
                            if(currentVersion != null && currentVersion.equals(latestVersion)) {

                                String fileName = "mementomori_" + latestVersion + ".apk";
                                File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);

                                // 残っている古いAPKファイルを削除
                                if (file.exists()) {
                                    try {
                                        Files.delete(Paths.get(file.getAbsolutePath()));
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }

                                launchMememori();
                            } else if(latestVersion == null) {
                                String errorMessage = "バージョン情報の解析に失敗しました";
                                Log.e(TAG, errorMessage);
                                statusText.setText(errorMessage);
                                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                            }else {
                                // メメントモリが最新バージョンでない場合、ダウンロードを開始
                                try {
                                    downloadApk();
                                } catch (IOException ignored) {
                                }
                            }
                        } else {
                            // メメントモリがインストールされていない場合、ダウンロードを開始
                            try {
                                downloadApk();
                            } catch (IOException ignored) {
                            }
                        }
                    });
                } else {
                    Log.e(TAG, "バージョン情報の取得に失敗");
                    runOnUiThread(() -> {
                        String errorMessage = "サーバーからの応答がありません";
                        statusText.setText(errorMessage);
                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "バージョン確認中にエラー発生", e);
                runOnUiThread(() -> {
                    String errorMessage = "エラー: " + e.getMessage();
                    statusText.setText(errorMessage);
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // バージョン情報を取得
    private String fetchVersionInfo() throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(VERSION_CHECK_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "MememoriLauncher/1.0");
            connection.setRequestProperty("Accept-Charset", "UTF-8");

            Log.d(TAG, "サーバーに接続中...");
            connection.connect();

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "HTTP応答コード: " + responseCode);

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "サーバーエラー: " + responseCode);
                return null;
            }

            // コンテンツタイプからエンコーディングを取得
            String contentType = connection.getContentType();
            String charset = "UTF-8";

            if (contentType != null) {
                String[] values = contentType.split(";");
                for (String value : values) {
                    value = value.trim();
                    if (value.toLowerCase().startsWith("charset=")) {
                        charset = value.substring("charset=".length());
                    }
                }
            }

            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), charset))) {
                char[] buffer = new char[1024];
                int length;
                while ((length = reader.read(buffer)) > 0) {
                    result.append(buffer, 0, length);
                }

                String response = result.toString();

                // 不要な制御文字を除去
                response = response.replaceAll("[\\p{C}&&[^\r\n\t]]", "");

                return response;
            }
        } catch (Exception e) {
            Log.e(TAG, "ネットワークエラー: " + e.getMessage(), e);
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // バージョンをjsファイルから抽出
    private String extractVersion(String versionInfo) {

        if (versionInfo == null || versionInfo.isEmpty()) {
            Log.e(TAG, "バージョン情報が空です");
            return null;
        }

        try {
            // apkVersionから直接バージョンを抽出
            Pattern pattern = Pattern.compile("var apkVersion = '([0-9.]+)';");
            Matcher matcher = pattern.matcher(versionInfo);

            if (matcher.find()) {
                String version = matcher.group(1);
                Log.d(TAG, "見つかったバージョン: " + version);
                return version;
            }

            // apkVersionから見つからない場合、downloadApkからバージョンを抽出
            pattern = Pattern.compile("/apps/mementomori_([0-9.]+)\\.apk");
            matcher = pattern.matcher(versionInfo);

            if (matcher.find()) {
                String version = matcher.group(1);
                Log.d(TAG, "downloadApkから抽出したバージョン: " + version);
                return version;
            }

            Log.e(TAG, "バージョン情報が見つかりません。受信データ: " + versionInfo);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "バージョン抽出中にエラー", e);
            return null;
        }
    }

    // APKをダウンロード
    private void downloadApk() throws IOException {

        String fileName = "mementomori_" + latestVersion + ".apk";
        String downloadUrl = BASE_DOWNLOAD_URL + fileName;
        Log.d(TAG, "ダウンロードURL: " + downloadUrl);

        File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);

        if (file.exists()) {
            Files.delete(Paths.get(file.getAbsolutePath()));
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(downloadUrl)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "ダウンロード失敗", e);
                runOnUiThread(() -> {
                    String errorMessage = "ダウンロード失敗: " + e.getMessage();
                    statusText.setText(errorMessage);
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    progressBar.setVisibility(View.GONE);
                });
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "サーバーエラー: " + response.code());
                    runOnUiThread(() -> {
                        String errorMessage = "ダウンロード失敗: " + response.code();
                        statusText.setText(errorMessage);
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                        progressBar.setVisibility(View.GONE);
                    });
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) {
                        throw new IOException("レスポンスボディがnullです");
                    }

                    long contentLength = responseBody.contentLength();
                    BufferedSink sink = Okio.buffer(Okio.sink(file));
                    Buffer buffer = new Buffer();
                    long totalBytesRead = 0;
                    long lastUpdateTime = System.currentTimeMillis();

                    while (true) {
                        long bytesRead = responseBody.source().read(buffer, 8192);
                        if (bytesRead == -1) break;

                        sink.write(buffer, bytesRead);
                        totalBytesRead += bytesRead;

                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTime >= 100) {
                            final int progress = contentLength > 0
                                ? (int) (totalBytesRead * 100 / contentLength)
                                : -1;

                            runOnUiThread(() -> {
                                if (progress != -1) {
                                    progressBar.setProgress(progress);
                                    statusText.setText("ダウンロード中... " + progress + "%");
                                }
                            });
                            lastUpdateTime = currentTime;
                        }
                    }

                    sink.flush();
                    sink.close();

                    runOnUiThread(() -> {
                        progressBar.setProgress(100);
                        statusText.setText("ダウンロード完了");
                        installApk(file);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "ファイル保存中にエラー", e);
                    runOnUiThread(() -> {
                        String errorMessage = "ファイル保存エラー: " + e.getMessage();
                        statusText.setText(errorMessage);
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                        progressBar.setVisibility(View.GONE);
                    });
                    if (file.exists()) {
                        Files.delete(Paths.get(file.getAbsolutePath()));
                    }
                }
            }
        });

        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("ダウンロードを開始します...");
    }

    // APKをインストール
    private void installApk(File file) {
        Log.d(TAG, "APKインストール開始: " + file.getAbsolutePath());

        Uri apkUri = FileProvider.getUriForFile(this,
                getPackageName() + ".provider", file);

        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "インストール開始エラー", e);
            Toast.makeText(this, "インストールを開始できません: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // メメントモリを起動
    private void launchMememori() {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(MememoriUtil.PACKAGE_NAME);

        if (intent != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                statusText.setText("メメントモリを起動しました");
                finish();
            } catch (Exception e) {
                statusText.setText("メメントモリが見つかりませんでした");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        executorService.shutdown();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
    }
}