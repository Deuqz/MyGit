package ru.hse.fmcs;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.*;

public class MyGitIndex {
    private Map<Path, String> newFiles;
    private Map<Path, String> modifiedFiles;
    private Set<Path> deletedFiles;

    public MyGitIndex() {
        newFiles = new HashMap<>();
        modifiedFiles = new HashMap<>();
        deletedFiles = new HashSet<>();
    }

    public boolean isEmpty() {
        return newFiles.isEmpty() && modifiedFiles.isEmpty() && deletedFiles.isEmpty();
    }

    public Map<Path, String> getNewFiles() {
        return newFiles;
    }

    public Map<Path, String> getModifiedFiles() {
        return modifiedFiles;
    }

    public Set<Path> getDeletedFiles() {
        return deletedFiles;
    }

    public boolean containsName(Path path) {
        return newFiles.containsKey(path) || modifiedFiles.containsKey(path) || deletedFiles.contains(path);
    }

    public boolean containsFile(Path path, String hash) {
        return filesContains(newFiles, path, hash) || filesContains(modifiedFiles, path, hash) || deletedFiles.contains(path);
    }

    public String add(Path path, String hash, MyGitFileMode mode) {
        String prevHash = null;
        if (mode == MyGitFileMode.NEW) {
            prevHash = newFiles.put(path, hash);
        } else if (mode == MyGitFileMode.MODIFIED) {
            prevHash = modifiedFiles.put(path, hash);
        } else {
            deletedFiles.add(path);
            newFiles.remove(path);
            modifiedFiles.remove(path);
        }
        return prevHash;
    }

    public void delete(Path path) {
        if (deletedFiles.contains(path)) {
            deletedFiles.remove(path);
        } else if (newFiles.containsKey(path)) {
            newFiles.remove(path);
        } else {
            modifiedFiles.remove(path);
        }
    }

    public String getFileHash(Path path) {
        if (newFiles.containsKey(path)) {
            return newFiles.get(path);
        } else {
            return modifiedFiles.get(path);
        }
    }

    public void clear() {
        newFiles.clear();
        modifiedFiles.clear();
    }

    private boolean filesContains(Map<Path, String> files, Path path, String hash) {
        return files.containsKey(path) && files.get(path).equals(hash);
    }
}
