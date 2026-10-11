/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import org.gradle.api.Project;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Config {
    private Config() { }

    // Synchronized build configuration for all modules
    public static final String buildToolsVersion = "37.0.0";
    public static final int compileSdkMajorVersion = 37;
    public static final int compileSdkMinorVersion = 1;
    public static final int minSdkVersion = 26;
    public static final int targetSdkVersion = 37;
    public static final int jvmTargetCompatibility = 17;

    public static String generateDebugVersionName() {
        return new SimpleDateFormat("1.0.yyww", Locale.US).format(new Date());
    }

    public static String releaseVersionName(Project project) {
        return project.hasProperty("versionName")
                ? (String) project.property("versionName")
                : "3.2";
    }

    /**
     * Keep locally distributed APK version codes monotonic even if the build
     * clock or timezone moves backward. The previous 2.98 arm64 APK used
     * 82550002; the variant offset adds 20,000 plus the ABI offset below.
    */
    public static int releaseVersionCode(Project project) {
        return project.hasProperty("versionCode")
                ? Integer.parseInt(String.valueOf(project.property("versionCode")))
                : 82540000;
    }
}
