/*
 * CRLauncher - https://github.com/CRLauncher/CRLauncher
 * Copyright (C) 2024 CRLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package me.theentropyshard.crlauncher.cosmic;

import me.theentropyshard.crlauncher.CRLauncher;
import me.theentropyshard.crlauncher.Settings;
import me.theentropyshard.crlauncher.cosmic.launcher.AbstractCosmicLauncher;
import me.theentropyshard.crlauncher.cosmic.launcher.CosmicLauncher;
import me.theentropyshard.crlauncher.cosmic.launcher.CosmicLauncherFactory;
import me.theentropyshard.crlauncher.cosmic.launcher.LaunchType;
import me.theentropyshard.crlauncher.cosmic.mods.Mod;
import me.theentropyshard.crlauncher.cosmic.mods.jar.JarMod;
import me.theentropyshard.crlauncher.cosmic.version.Version;
import me.theentropyshard.crlauncher.cosmic.version.VersionList;
import me.theentropyshard.crlauncher.cosmic.version.VersionManager;
import me.theentropyshard.crlauncher.gui.LauncherConsole;
import me.theentropyshard.crlauncher.gui.components.InstanceItem;
import me.theentropyshard.crlauncher.gui.dialogs.ProgressDialog;
import me.theentropyshard.crlauncher.instance.Instance;
import me.theentropyshard.crlauncher.instance.InstanceType;
import me.theentropyshard.crlauncher.java.JavaLocator;
import me.theentropyshard.crlauncher.logging.Log;
import me.theentropyshard.crlauncher.utils.FileUtils;
import me.theentropyshard.crlauncher.utils.SystemProperty;
import me.theentropyshard.crlauncher.utils.TimeUtils;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CosmicRunner extends Thread {


    private final Instance instance;
    private final InstanceItem item;

    private Path clientCopyTmp;

    public CosmicRunner(Instance instance, InstanceItem item) {
        this.instance = instance;
        this.item = item;

        this.setName("Cosmic Reach run thread");
    }

    @Override
    public synchronized void start() {
        if (this.instance.isRunning()) {
            return;
        }

        this.instance.setRunning(true);
        this.item.setEnabled(false);

        super.start();
    }

    @Override
    public void run() {
        VersionManager versionManager = CRLauncher.getInstance().getVersionManager();

        this.updateCosmicVersion();

        try {
            Version version = versionManager.getVersion(this.instance.getCosmicVersion());

            ProgressDialog dialog = new ProgressDialog("Downloading Cosmic Reach");
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
            versionManager.downloadVersion(version, dialog);
            dialog.getDialog().dispose();

            Path saveDirPath = this.instance.getCosmicDir();

            Path versionsDir = CRLauncher.getInstance().getVersionsDir();
            Path clientPath = versionsDir.resolve(version.getId()).resolve(version.getId() + ".jar").toAbsolutePath();

            this.instance.setLastTimePlayed(LocalDateTime.now());

            CosmicLauncher launcher;

            String javaPath = this.instance.getJavaPath();
            if (javaPath == null || javaPath.isEmpty()) {
                javaPath = JavaLocator.getJavaPath();
                this.instance.setJavaPath(javaPath);
            }

            if (this.instance.getType() == InstanceType.VANILLA) {
                clientPath = this.applyJarMods(version, versionsDir);

                launcher = CosmicLauncherFactory.getLauncher(
                    javaPath,
                    LaunchType.VANILLA,
                    saveDirPath,
                    saveDirPath,
                    clientPath
                );
            } else {
                launcher = switch (this.instance.getType()) {
                    case FABRIC -> CosmicLauncherFactory.getLauncher(
                        javaPath,
                        LaunchType.FABRIC,
                        saveDirPath,
                        saveDirPath,
                        clientPath,
                        this.instance.getFabricModsDir(),
                        this.instance.getFabricVersion()
                    );
                    case QUILT -> CosmicLauncherFactory.getLauncher(
                        javaPath,
                        LaunchType.QUILT,
                        saveDirPath,
                        saveDirPath,
                        clientPath,
                        this.instance.getQuiltModsDir(),
                        this.instance.getQuiltVersion()
                    );
                    case PUZZLE -> CosmicLauncherFactory.getLauncher(
                        javaPath,
                        LaunchType.PUZZLE,
                        saveDirPath,
                        saveDirPath,
                        clientPath,
                        this.instance.getPuzzleModsDir(),
                        this.instance.getPuzzleVersion()
                    );
                    default -> throw new IllegalArgumentException("Unknown instance type: " + this.instance.getType());
                };

                switch (this.instance.getType()) {
                    case VANILLA -> {

                    }
                    case FABRIC ->
                        this.updateMods(this.instance.getFabricMods(), this.instance.getFabricModsDir(), this.instance.getDisabledFabricModsDir());
                    case QUILT ->
                        this.updateMods(this.instance.getQuiltMods(), this.instance.getQuiltModsDir(), this.instance.getDisabledQuiltModsDir());
                    case PUZZLE ->
                        this.updateMods(this.instance.getPuzzleMods(), this.instance.getPuzzleModsDir(), this.instance.getDisabledPuzzleModsDir());
                }
            }

            Settings settings = CRLauncher.getInstance().getSettings();

            int launchOption = settings.whenCRLaunchesOption;

            boolean consoleWasOpen = LauncherConsole.instance.getFrame().isVisible();

            switch (launchOption) {
                case 1 -> CRLauncher.frame.setVisible(false);
                case 2 -> {
                    CRLauncher.frame.setVisible(false);
                    LauncherConsole.instance.setVisible(false);
                }
            }

            if (launcher instanceof AbstractCosmicLauncher abstractLauncher) {
                String title = this.instance.getCustomWindowTitle();

                if (title != null && !title.trim().isEmpty()) {
                    abstractLauncher.defineProperty(new SystemProperty("crloader.windowTitle", title));
                }
            }

            long start = System.currentTimeMillis();

            int exitCode = launcher.launch(line -> {
                InstanceType type = this.instance.getType();
                if (type == InstanceType.VANILLA || type == InstanceType.FABRIC) {
                    Log.cosmicReachVanilla(line);
                } else {
                    Log.cosmicReachModded(line);
                }
            }, launchOption == 3);

            long end = System.currentTimeMillis();

            int exitsOption = settings.whenCRExitsOption;
            if (exitsOption == 0) {
                switch (launchOption) {
                    case 1 -> CRLauncher.frame.setVisible(true);
                    case 2 -> {
                        if (consoleWasOpen) {
                            LauncherConsole.instance.setVisible(true);
                        }
                        CRLauncher.frame.setVisible(true);
                    }
                }
            }

            Log.info("Cosmic Reach process finished with exit code " + exitCode);

            long timePlayedSeconds = (end - start) / 1000;
            String timePlayed = TimeUtils.getHoursMinutesSeconds(timePlayedSeconds);
            if (!timePlayed.trim().isEmpty()) {
                Log.info("You played for " + timePlayed + "!");
            }

            this.instance.updatePlaytime(timePlayedSeconds);
            this.instance.save();

            if (exitCode == 0 && exitsOption == 1) {
                CRLauncher.getInstance().shutdown();
            }
        } catch (Exception e) {
            Log.error("Exception occurred while trying to start Cosmic Reach", e);
        } finally {
            this.instance.setRunning(false);
            this.item.setEnabled(true);

            if (this.clientCopyTmp != null && Files.exists(this.clientCopyTmp)) {
                try {
                    Files.delete(this.clientCopyTmp);
                } catch (IOException e) {
                    Log.error("Unable to delete temporary client", e);
                }
            }
        }
    }

    private void updateCosmicVersion() {
        VersionManager versionManager = CRLauncher.getInstance().getVersionManager();

        if (this.instance.isAutoUpdateToLatest()) {
            VersionList versionList = versionManager.getVersionList();

            if (versionList == null) {
                try {
                    versionManager.loadRemoteVersions();
                    versionList = versionManager.getVersionList();
                } catch (IOException e) {
                    Log.error("Could not load remote versions, no auto-update performed", e);

                    return;
                }
            }

            this.instance.setCosmicVersion(versionList.getLatest().getPreAlpha());
        }
    }

    private Path applyJarMods(Version version, Path clientsDir) {
        Path originalClientPath = clientsDir.resolve(version.getId()).resolve(version.getId() + ".jar").toAbsolutePath();

        List<JarMod> jarMods = this.instance.getJarMods();

        if (jarMods == null || jarMods.isEmpty() || jarMods.stream().noneMatch(JarMod::isActive)) {
            return originalClientPath;
        } else {
            try {
                this.clientCopyTmp = Files.copy(originalClientPath, this.instance.getWorkDir()
                    .resolve(originalClientPath.getFileName().toString() + System.currentTimeMillis() + ".jar"));

                List<File> zipFilesToMerge = new ArrayList<>();

                for (JarMod jarMod : jarMods) {
                    if (!jarMod.isActive()) {
                        continue;
                    }

                    zipFilesToMerge.add(Paths.get(jarMod.getFullPath()).toFile());
                }

                try (ZipFile copyZip = new ZipFile(this.clientCopyTmp.toFile())) {
                    for (File modFile : zipFilesToMerge) {
                        Path unpackDir = this.instance.getWorkDir().resolve(modFile.getName().replace(".", "_"));
                        try (ZipFile modZip = new ZipFile(modFile)) {
                            if (Files.exists(unpackDir)) {
                                FileUtils.delete(unpackDir);
                            }
                            FileUtils.createDirectoryIfNotExists(unpackDir);

                            modZip.extractAll(unpackDir.toAbsolutePath().toString());
                        }

                        List<Path> modFiles = FileUtils.walk(unpackDir);

                        ZipParameters zipParameters = new ZipParameters();

                        for (Path modFileToAdd : modFiles) {
                            String relative = unpackDir.toAbsolutePath().toUri().relativize(modFileToAdd.toAbsolutePath().toUri()).getPath();
                            zipParameters.setFileNameInZip(relative);
                            copyZip.addFile(modFileToAdd.toFile(), zipParameters);
                        }

                        FileUtils.delete(unpackDir);
                    }
                }

                return this.clientCopyTmp;
            } catch (IOException e) {
                Log.error("Exception while applying jar mods", e);
            }
        }

        return originalClientPath;
    }

    private void updateMods(List<? extends Mod> mods, Path enabledModsDir, Path disabledModsDir) throws IOException {
        if (mods.isEmpty()) {
            return;
        }

        FileUtils.createDirectoryIfNotExists(enabledModsDir);
        FileUtils.createDirectoryIfNotExists(disabledModsDir);

        for (Mod mod : mods) {
            Path filePath = Paths.get(mod.getFilePath());

            if (!Files.exists(filePath)) {
                Log.warn("Mod at '" + filePath + "' does not exist!");

                continue;
            }

            if ((mod.isActive() && filePath.startsWith(enabledModsDir)) ||
                (!mod.isActive() && filePath.startsWith(disabledModsDir))) {

                continue;
            }

            Path destinationDir;

            if (mod.isActive()) {
                destinationDir = enabledModsDir.resolve(filePath.getFileName());
            } else {
                destinationDir = disabledModsDir.resolve(filePath.getFileName());
            }

            filePath = Files.move(filePath, destinationDir, StandardCopyOption.REPLACE_EXISTING);
            mod.setFilePath(filePath.toString());
        }
    }
}
