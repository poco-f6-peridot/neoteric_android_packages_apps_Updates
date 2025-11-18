package org.neoteric.ota;

import static android.provider.OpenableColumns.DISPLAY_NAME;

import android.app.Activity;
import android.app.ProgressDialog;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.neoteric.ota.controller.UpdaterController;
import org.neoteric.ota.misc.Utils;
import org.neoteric.ota.model.Update;
import org.neoteric.ota.model.UpdateInfo;
import org.neoteric.ota.model.UpdateStatus;

import org.neoteric.ota.prefs.CardPreference;
import org.neoteric.ota.prefs.ChangelogPreference;
import org.neoteric.ota.prefs.UpdaterCardPreference;
import org.neoteric.ota.prefs.RoundCornerPreferenceAdapter;

import org.neoteric.ota.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class UpdatesFragment extends PreferenceFragmentCompat {

    private static final int SELECT_FILE = 1001;
    private static final String TAG = "UpdatesFragment";
    private static final String MIME_ZIP = "application/zip";
    private static final String METADATA_PATH = "META-INF/com/android/metadata";

    private static final String KEY_UPDATER_PREF = "updater_card";
    private static final String KEY_CHANGELOG = "changelog";
    private static final String KEY_UPDATER_CATEGORY = "updater_cards";
    private static final String KEY_LOCAL_UPDATE = "local_update";
    private static final String KEY_MAINTAINER = "maintainer";
    private static final String KEY_DONATE = "donate";
    private static final String KEY_GROUP = "group";

    private UpdaterCardPreference mUpdaterPref;
    private ChangelogPreference mChangelogPref;
    private PreferenceCategory mUpdaterPrefCategory;

    private CardPreference localUpdateCard;
    private CardPreference maintainerCard;
    private CardPreference donateCard;
    private CardPreference groupCard;

    private LocalBroadcastManager mBroadcastManager;

    private UpdaterController mUpdaterController;
    private UpdateInfo mUpdate;

    private String[] deviceList;
    private String[] maintainerNameList;
    private String[] maintainerLinkList;
    private String[] donateList;
    private String[] groupList;
    private int device_index = -1;

    private Thread workingThread;

    private ProgressDialog importDialog;

    private UpdateListener mListener;
    public interface UpdateListener {
        public void addedUpdate();
        public void importDisabled();
        public void importFailed();
    }

    @Override
    public void onAttach(Activity activity) {
        mListener = (UpdateListener) activity;
        super.onAttach(activity);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUpdaterController = UpdaterController.getInstance(getContext());
        mBroadcastManager = LocalBroadcastManager.getInstance(getContext());
        deviceList = getContext().getResources().getStringArray(
                R.array.config_device_list);
        maintainerNameList = getContext().getResources().getStringArray(
                R.array.config_maintainer_name_list);
        maintainerLinkList = getContext().getResources().getStringArray(
                R.array.config_maintainer_link_list);
        donateList = getContext().getResources().getStringArray(
                R.array.config_donate_list);
        groupList = getContext().getResources().getStringArray(
                R.array.config_group_list);
        device_index = getDeviceIndex();
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String key) {
        setPreferencesFromResource(R.xml.updates_prefs, key);
        mUpdaterPref = findPreference(KEY_UPDATER_PREF);
        mUpdaterPrefCategory = findPreference(KEY_UPDATER_CATEGORY);

        localUpdateCard = findPreference(KEY_LOCAL_UPDATE);
        maintainerCard = findPreference(KEY_MAINTAINER);
        donateCard = findPreference(KEY_DONATE);
        groupCard = findPreference(KEY_GROUP);
    }

    @Override
    protected RecyclerView.Adapter onCreateAdapter(PreferenceScreen preferenceScreen) {
        return new RoundCornerPreferenceAdapter(preferenceScreen);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if (requestCode == SELECT_FILE && resultCode == Activity.RESULT_OK && resultData != null) {
            Uri uri = resultData.getData();
            Cursor cursor = getActivity().getContentResolver().query(uri,
                    null, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String fileName = cursor.getString(cursor.getColumnIndex(DISPLAY_NAME));
                if (importDialog != null && importDialog.isShowing()) {
                    importDialog.dismiss();
                }
                importDialog = new ProgressDialog(
                        new ContextThemeWrapper(getContext(),
                                R.style.AppTheme_AlertDialogStyle));
                importDialog.setTitle(getString(R.string.local_update_title));
                importDialog.setMessage(getString(R.string.local_update_import_progress));
                importDialog.setIndeterminate(true);
                importDialog.setCancelable(false);
                importDialog.show();
                workingThread = new Thread(() -> {
                    File importedFile = null;
                    try {
                        importedFile = importFile(uri, fileName);
                        verifyPackage(importedFile);

                        final Runnable deleteUpdate = () -> Utils.cleanupDownloadsDir(getContext());

                        final Update update = buildLocalUpdate(importedFile, fileName);
                        addUpdate(update);
                        getActivity().runOnUiThread(() -> {
                            if (importDialog != null) {
                                importDialog.dismiss();
                                importDialog = null;
                            }
                            new AlertDialog.Builder(getContext(), R.style.AppTheme_AlertDialogStyle)
                                .setTitle(R.string.local_update_title)
                                .setMessage(getString(R.string.local_update_import_success, update.getName()))
                                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                                    mListener.addedUpdate();
                                })
                                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                                .setOnCancelListener((dialog) -> deleteUpdate.run())
                                .show();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to import update package", e);
                        // Do not store invalid update
                        if (importedFile != null) {
                            importedFile.delete();
                        }
        
                        getActivity().runOnUiThread(() -> {
                            if (importDialog != null) {
                                importDialog.dismiss();
                                importDialog = null;
                            }
                            mListener.importFailed();
                        });
                    }
                });
                workingThread.start();
            }
        }
    }

    @Override
    public void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            if (workingThread != null && workingThread.isAlive()) {
                workingThread.interrupt();
                workingThread = null;
            }
        }

        super.onPause();
    }

    public void refreshUpdaterPref() {
        if (mUpdaterPref != null) {
            mUpdaterPref.notifyUpdateChanged();
        }
    }

    private void fetchChangelog(long timestamp) {
        if (!Utils.isNetworkAvailable(getContext())) {
            showSnackbar(R.string.fetch_changelog_failed, Snackbar.LENGTH_LONG);
            return;
        }

        new Thread(() -> {
            String changelog = Utils.getChangelog(getContext(), timestamp);

            Activity activity = getActivity();
            if (activity == null) return;

            activity.runOnUiThread(() -> {
                if (changelog != null && !changelog.isEmpty()) {
                    if (mUpdaterPrefCategory != null && mChangelogPref == null) {
                        mChangelogPref = new ChangelogPreference(getContext());
                        mChangelogPref.setKey(KEY_CHANGELOG);
                        mChangelogPref.setTitle(R.string.fetch_changelog_title);
                        mUpdaterPrefCategory.addPreference(mChangelogPref);
                    }
                    if (mChangelogPref != null) {
                        mChangelogPref.setSummary(changelog);
                    }
                } else {
                    showSnackbar(R.string.fetch_changelog_failed, Snackbar.LENGTH_LONG);
                }
            });
        }).start();
    }

    public void showChangelog(boolean value) {
        if (value) {
            if (mUpdaterController != null) {
                mUpdate = mUpdaterController.getCurrentUpdate();
                if (mUpdate != null) {
                    fetchChangelog(mUpdate.getTimestamp());
                } else {
                    showSnackbar(R.string.snack_no_updates_found, Snackbar.LENGTH_SHORT);
                }
            }
        } else if (mUpdaterPrefCategory != null && mChangelogPref != null) {
            mUpdaterPrefCategory.removePreference(mChangelogPref);
            mChangelogPref = null;
        }
    }

    public void hideUpdaterPref() {
        if (mUpdaterPref != null) {
            mUpdaterPref.setVisible(false);
        }
    }

    public void showUpdaterPref() {
        if (mUpdaterPref != null) {
            mUpdaterPref.setVisible(true);
        }
    }

    public void setDownloadId(@NonNull String downloadId) {
        if (mUpdaterPref != null) {
            mUpdaterPref.setDownloadId(downloadId);
        }
    }

    private Update buildLocalUpdate(File file, String fileName) {
        final Update update = new Update();
        String regex = "\\d{8}_\\d{6}";
        Matcher matcher = Pattern.compile(regex).matcher(fileName);
        long timeStamp = Instant.now().getEpochSecond();
        if (matcher.find()) {
            String timeStr = matcher.group();
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
            Instant instant = Instant.from(dtf.parse(timeStr));
            timeStamp = instant.getEpochSecond();
        }
        update.setName(fileName);
        update.setFile(file);
        update.setFileSize(file.length());
        update.setDownloadId(Update.LOCAL_ID);
        update.setTimestamp(timeStamp);
        update.setStatus(UpdateStatus.VERIFIED);
        update.setVersion(Utils.getVersion());
        return update;
    }

    private void addUpdate(Update update) {
        Utils.setPersistentStatus(getContext(), UpdateStatus.Persistent.VERIFIED);
        mUpdaterController.addUpdate(update);
    }

    private String readZippedFile(File file, String path) throws IOException {
        final StringBuilder sb = new StringBuilder();
        InputStream iStream = null;

        try {
            final ZipFile zip = new ZipFile(file);
            final Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                final ZipEntry entry = iterator.nextElement();
                if (!METADATA_PATH.equals(entry.getName())) {
                    continue;
                }

                iStream = zip.getInputStream(entry);
                break;
            }

            if (iStream == null) {
                throw new FileNotFoundException("Couldn't find " + path + " in " + file.getName());
            }

            final byte[] buffer = new byte[1024];
            int read;
            while ((read = iStream.read(buffer)) > 0) {
                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file from zip package", e);
            throw e;
        } finally {
            if (iStream != null) {
                iStream.close();
            }
        }

        return sb.toString();
    }

    @SuppressLint("SetWorldReadable")
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private File importFile(Uri uri, String fileName) throws IOException {
        final ParcelFileDescriptor parcelDescriptor = getActivity().getContentResolver()
                .openFileDescriptor(uri, "r");
        if (parcelDescriptor == null) {
            throw new IOException("Failed to obtain fileDescriptor");
        }

        final FileInputStream iStream = new FileInputStream(parcelDescriptor
                .getFileDescriptor());
        final File outFile = new File(Utils.getDownloadPath(), fileName);
        if (outFile.exists()) {
            outFile.delete();
        }
        final FileOutputStream oStream = new FileOutputStream(outFile);

        int read;
        final byte[] buffer = new byte[4096];
        while ((read = iStream.read(buffer)) > 0) {
            oStream.write(buffer, 0, read);
        }
        oStream.flush();
        oStream.close();
        iStream.close();

        outFile.setReadable(true, false);

        return outFile;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void verifyPackage(File file) throws Exception {
        try {
            android.os.RecoverySystem.verifyPackage(file, null, null);
        } catch (Exception e) {
            if (file.exists()) {
                file.delete();
                throw new Exception("Verification failed, file has been deleted");
            } else {
                throw e;
            }
        }
    }

    private int getDeviceIndex() {
        final String device = Utils.getDevice();
        if (device == null || device.isEmpty()) return -1;
        for (int i = 0; i < deviceList.length; ++i) {
            if (device.equals(deviceList[i])) return i;
        }
        return -1;
    }

    void updateCardPrefs() {
        localUpdateCard.setOnPreferenceClickListener(pref -> {
            if (mUpdaterController.isInstallingUpdate() ||
                    mUpdaterController.isDownloading()) {
                mListener.importDisabled();
            } else {
                final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(MIME_ZIP);
                startActivityForResult(intent, SELECT_FILE);
            }
            return true;
        });
        localUpdateCard.setVisible(true);

        if (device_index != -1) {
            maintainerCard.setOnPreferenceClickListener(pref -> {
                openUrl(maintainerLinkList[device_index]);
                return true;
            });
            maintainerCard.setSummary(maintainerNameList[device_index]);

            donateCard.setOnPreferenceClickListener(pref -> {
                openUrl(donateList[device_index]);
                return true;
            });
            donateCard.setVisible(true);

            groupCard.setOnPreferenceClickListener(pref -> {
                openUrl(groupList[device_index]);
                return true;
            });
            groupCard.setVisible(true);
        } else {
            maintainerCard.setSummary(getContext().getResources().getString(
                    R.string.maintainer_info_unknown));
            maintainerCard.setEnabled(false);
        }
        maintainerCard.setVisible(true);
    }

    private void showSnackbar(int stringId, int duration) {
        Snackbar.make(getActivity().findViewById(R.id.main_container), stringId, duration).show();
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ex) {
            showSnackbar(R.string.error_open_url, Snackbar.LENGTH_SHORT);
        }
    }
}
