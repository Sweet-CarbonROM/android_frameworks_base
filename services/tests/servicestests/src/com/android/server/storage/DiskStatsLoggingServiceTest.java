/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.storage;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.when;

import android.app.job.JobService;
import android.app.usage.ExternalStorageStats;
import android.app.usage.StorageStatsManager;
import android.content.pm.PackageStats;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.server.storage.DiskStatsLoggingService.LogRunnable;

import libcore.io.IoUtils;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;

@RunWith(JUnit4.class)
public class DiskStatsLoggingServiceTest extends AndroidTestCase {
    @Rule public TemporaryFolder mTemporaryFolder;
    @Rule public TemporaryFolder mDownloads;
    @Mock private AppCollector mCollector;
    @Mock private JobService mJobService;
    @Mock private StorageStatsManager mSsm;
    private ExternalStorageStats mStorageStats;
    private File mInputFile;


    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mTemporaryFolder = new TemporaryFolder();
        mTemporaryFolder.create();
        mInputFile = mTemporaryFolder.newFile();
        mDownloads = new TemporaryFolder();
        mDownloads.create();
        mStorageStats = new ExternalStorageStats();
        when(mSsm.queryExternalStatsForUser(isNull(String.class), any(UserHandle.class)))
                .thenReturn(mStorageStats);
        when(mJobService.getSystemService(anyString())).thenReturn(mSsm);
    }

    @Test
    public void testEmptyLog() throws Exception {
        LogRunnable task = new LogRunnable();
        task.setAppCollector(mCollector);
        task.setDownloadsDirectory(mDownloads.getRoot());
        task.setLogOutputFile(mInputFile);
        task.setSystemSize(0L);
        task.setContext(mJobService);
        task.run();

        JSONObject json = getJsonOutput();
        assertThat(json.getLong(DiskStatsFileLogger.PHOTOS_KEY)).isEqualTo(0L);
        assertThat(json.getLong(DiskStatsFileLogger.VIDEOS_KEY)).isEqualTo(0L);
        assertThat(json.getLong(DiskStatsFileLogger.AUDIO_KEY)).isEqualTo(0L);
        assertThat(json.getLong(DiskStatsFileLogger.DOWNLOADS_KEY)).isEqualTo(0L);
        assertThat(json.getLong(DiskStatsFileLogger.SYSTEM_KEY)).isEqualTo(0L);
        assertThat(json.getLong(DiskStatsFileLogger.MISC_KEY)).isEqualTo(0L);
        assertThat(json.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY)).isEqualTo(0L);
        assertThat(json.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY)).isEqualTo(0L);
        assertThat(
                json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY).length()).isEqualTo(0L);
        assertThat(json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY).length()).isEqualTo(0L);
        assertThat(json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY).length()).isEqualTo(0L);
    }

    @Test
    public void testPopulatedLogTask() throws Exception {
        // Write data to directories.
        writeDataToFile(mDownloads.newFile(), "lol");
        mStorageStats.audioBytes = 6L;
        mStorageStats.imageBytes = 4L;
        mStorageStats.videoBytes = 5L;
        mStorageStats.totalBytes = 22L;

        // Write apps.
        ArrayList<PackageStats> apps = new ArrayList<>();
        PackageStats testApp = new PackageStats("com.test.app");
        testApp.dataSize = 5L;
        testApp.cacheSize = 55L;
        testApp.codeSize = 10L;
        testApp.userHandle = UserHandle.USER_SYSTEM;
        apps.add(testApp);
        when(mCollector.getPackageStats(anyLong())).thenReturn(apps);

        LogRunnable task = new LogRunnable();
        task.setAppCollector(mCollector);
        task.setDownloadsDirectory(mDownloads.getRoot());
        task.setLogOutputFile(mInputFile);
        task.setSystemSize(10L);
        task.setContext(mJobService);
        task.run();

        JSONObject json = getJsonOutput();
        assertThat(json.getLong(DiskStatsFileLogger.PHOTOS_KEY)).isEqualTo(4L);
        assertThat(json.getLong(DiskStatsFileLogger.VIDEOS_KEY)).isEqualTo(5L);
        assertThat(json.getLong(DiskStatsFileLogger.AUDIO_KEY)).isEqualTo(6L);
        assertThat(json.getLong(DiskStatsFileLogger.DOWNLOADS_KEY)).isEqualTo(3L);
        assertThat(json.getLong(DiskStatsFileLogger.SYSTEM_KEY)).isEqualTo(10L);
        assertThat(json.getLong(DiskStatsFileLogger.MISC_KEY)).isEqualTo(7L);
        assertThat(json.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY)).isEqualTo(15L);
        assertThat(json.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY)).isEqualTo(55L);
        assertThat(
                json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY).length()).isEqualTo(1L);
        assertThat(json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY).length()).isEqualTo(1L);
        assertThat(json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY).length()).isEqualTo(1L);
    }

    @Test
    public void testDontCrashOnPackageStatsTimeout() throws Exception {
        when(mCollector.getPackageStats(anyInt())).thenReturn(null);

        LogRunnable task = new LogRunnable();
        task.setAppCollector(mCollector);
        task.setDownloadsDirectory(mDownloads.getRoot());
        task.setLogOutputFile(mInputFile);
        task.setSystemSize(10L);
        task.setContext(mJobService);
        task.run();

        // No exception should be thrown.
    }

    private void writeDataToFile(File f, String data) throws Exception{
        PrintStream out = new PrintStream(f);
        out.print(data);
        out.close();
    }

    private JSONObject getJsonOutput() throws Exception {
        return new JSONObject(IoUtils.readFileAsString(mInputFile.getAbsolutePath()));
    }
}
