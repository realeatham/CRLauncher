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

package me.theentropyshard.crlauncher.instance;

import me.theentropyshard.crlauncher.java.JavaLocator;
import me.theentropyshard.crlauncher.logging.Log;
import me.theentropyshard.crlauncher.utils.FileUtils;
import me.theentropyshard.crlauncher.utils.SemanticVersion;
import me.theentropyshard.crlauncher.utils.StringUtils;
import me.theentropyshard.crlauncher.utils.json.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InstanceManager {
    

    private final Path workDir;
    private final List<Instance> instances;
    private final Map<String, Instance> instancesByName;

    public InstanceManager(Path workDir) {
        this.workDir = workDir;
        this.instances = new ArrayList<>();
        this.instancesByName = new HashMap<>();
    }

    public void load() throws IOException {
        List<Path> paths = FileUtils.list(this.workDir);

        for (Path path : paths) {
            if (!Files.isDirectory(path)) {
                continue;
            }

            Path instanceFile = path.resolve("instance.json");
            if (!Files.exists(instanceFile)) {
                continue;
            }

            Instance instance = Json.parse(FileUtils.readUtf8(instanceFile), Instance.class);
            instance.setWorkDir(path);

            this.cacheInstance(instance);
        }
    }

    public void reload() throws IOException {
        this.uncacheAll();
        this.load();
    }

    private void cacheInstance(Instance instance) {
        if (this.instancesByName.containsKey(instance.getName())) {
            return;
        }

        this.instances.add(instance);
        this.instancesByName.put(instance.getName(), instance);
    }

    private void uncacheInstance(Instance instance) {
        if (!this.instancesByName.containsKey(instance.getName())) {
            return;
        }

        this.instances.remove(instance);
        this.instancesByName.remove(instance.getName());
    }

    private void uncacheAll() {
        this.instances.clear();
        this.instancesByName.clear();
    }

    private Path findFreeName(String suggestion) {
        Path path = this.workDir.resolve(suggestion);

        if (Files.exists(path)) {
            suggestion = suggestion + "_";
            path = this.workDir.resolve(suggestion);

            if (!Files.exists(path)) {
                return path;
            }

            return this.findFreeName(suggestion);
        }

        return path;
    }

    private Path getInstanceWorkDir(String suggestedName, String cosmicVersion) {
        String cleanName = FileUtils.sanitizeFileName(suggestedName);

        if (cleanName.isEmpty()) {
            cleanName = "instance" + cosmicVersion;
        }

        Path freeName;

        try {
            freeName = this.findFreeName(cleanName);
        } catch (StackOverflowError | Exception e) {
            Log.warn("Unable to find free name for instance: " + e.getMessage());

            freeName = this.workDir.resolve(StringUtils.getRandomString(10));
        }

        return freeName;
    }

    public void createInstance(String name, String groupName, String cosmicVersion, boolean autoUpdate) throws
            IOException,
            InstanceAlreadyExistsException {

        if (this.instancesByName.containsKey(name)) {
            throw new InstanceAlreadyExistsException(name);
        }

        Instance instance = new Instance(name, groupName, cosmicVersion);
        instance.setWorkDir(this.getInstanceWorkDir(name, cosmicVersion));
        instance.setAutoUpdateToLatest(autoUpdate);
        instance.setJavaPath(JavaLocator.getJavaPath());

        this.cacheInstance(instance);

        FileUtils.createDirectoryIfNotExists(instance.getWorkDir());
        FileUtils.createDirectoryIfNotExists(instance.getCosmicDir());
        FileUtils.createDirectoryIfNotExists(instance.getJarModsDir());

        Path modsDir = instance.getCosmicDir().resolve("mods");
        FileUtils.createDirectoryIfNotExists(modsDir);

        try {
            SemanticVersion version = SemanticVersion.parse(cosmicVersion);

            if (version.isLowerThan(SemanticVersion.parse("0.3.0"))) {
                FileUtils.createDirectoryIfNotExists(modsDir.resolve("assets"));
            }
        } catch (Exception ignored) {

        }

        instance.save();
    }

    public void removeInstance(String name) throws IOException {
        Instance instance = this.getInstanceByName(name);

        if (instance == null) {
            return;
        }

        FileUtils.delete(instance.getWorkDir());

        this.uncacheInstance(instance);
    }

    public boolean renameInstance(Instance instance, String newName) throws IOException {
        this.uncacheInstance(instance);

        Path newInstanceDir = this.getInstanceWorkDir(newName, instance.getCosmicVersion());

        Files.move(instance.getWorkDir(), newInstanceDir, StandardCopyOption.REPLACE_EXISTING);

        instance.setWorkDir(newInstanceDir);

        boolean invalidName = !newInstanceDir.endsWith(newName);

        if (invalidName) {
            instance.setName(newInstanceDir.getFileName().toString());
        } else {
            instance.setName(newName);
        }

        this.cacheInstance(instance);

        return invalidName;
    }

    public Instance getInstanceByName(String name) {
        return this.instancesByName.get(name);
    }

    public List<Instance> getInstances() {
        return this.instances;
    }
}
