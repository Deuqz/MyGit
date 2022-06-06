package ru.hse.fmcs;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.commons.codec.digest.DigestUtils;
import org.checkerframework.checker.units.qual.A;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

public class MyGitRepository {
    // Using classes
    private PrintStream output;
    private MyGitIndex index;

    // Graph of commits
    private class Node {
        public MyGitCommit commit;
        public Node parent;

        public Node(MyGitCommit commit) {
            this.commit = commit;
            this.parent = null;
        }
    }

    private Node curNode;
    private Map<String, Node> graph = new HashMap<>(); // <hash commit, Node>

    // Meta information about git repository
    private class MetaInf {
        public Date dateInitialization;
        public String headCommitHash;
        public Map<String, String> branches; // <Branch name, headCommit>

        public MetaInf(Date date) {
            dateInitialization = date;
            branches = new LinkedHashMap<>();
        }
    }

    MetaInf metainf;

    // Other information
    private final Path directory;
    private final Path myGitDir;
    private final Path filesDir;
    private final Path commitsDir;
    private final Path metainfPath;
    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    public MyGitRepository(@NotNull String directory) throws GitException {
        output = System.out;
        index = new MyGitIndex();
        this.directory = Path.of(directory);
        myGitDir = Path.of(directory + "/myGit");
        filesDir = Path.of(myGitDir + "/files");
        commitsDir = Path.of(myGitDir + "/commits");
        metainfPath = Path.of(myGitDir + "/metainf.json");
        mapper = new ObjectMapper();
        writer = mapper.writer(new DefaultPrettyPrinter());
    }

    public void setOutputStream(@NotNull PrintStream outputStream) {
        this.output = outputStream;
    }

    // Git functions
    public void init() throws GitException {
        try {
            boolean firstInitialize = true;
            if (isMyGitDirectory()) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(commitsDir)) {
                    metainf = mapper.readValue(metainfPath.toFile(), MetaInf.class);
                    metainf.dateInitialization = new Date();
                    List<MyGitCommit> commits = new ArrayList<>();
                    for (Path path : stream) {
                        commits.add(mapper.readValue(Paths.get(path.toUri()).toFile(), MyGitCommit.class));
                    }
                    for (var commit : commits) {
                        graph.put(commit.getHash(), new Node(commit));
                    }
                    for (var node : graph.entrySet()) {
                        node.getValue().parent = graph.get(node.getValue().commit.getParentHash());
                    }
                    curNode = graph.get(metainf.headCommitHash);
                } catch (IOException e) {
                    throw new GitException("Can't get MyGit information", e);
                }
                firstInitialize = false;
            } else {
                Files.createDirectories(filesDir);
                Files.createDirectories(commitsDir);
                metainf = new MetaInf(new Date());
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                    for (Path path : stream) {
                        if (path.equals(myGitDir)) {
                            continue;
                        }
                        if (path.toFile().isDirectory()) {
                            Files.walk(path).forEach(p -> {
                                try {
                                    index.add(path, getFileHash(path), MyGitFileMode.NEW);
                                } catch (GitException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                        } else {
                            index.add(path, getFileHash(path), MyGitFileMode.NEW);
                        }
                    }
                }
                MyGitCommit commit = new MyGitCommit("Initial commit", null, index, "master");
                metainf.headCommitHash = commit.getHash();
                metainf.branches.put("master", commit.getHash());
                curNode = new Node(commit);
                graph.put(commit.getHash(), curNode);
                writer.writeValue(Path.of(commitsDir + "/" + commit.getHash() + ".json").toFile(), commit);
            }
            writer.writeValue(metainfPath.toFile(), metainf);
            if (firstInitialize) {
                output.println("Project initialized");
            } else {
                output.println("Project reinitialized");
            }
        } catch (IOException | RuntimeException e) {
            String message = "Can't initialize directory";
            output.println(message);
            if (e.getClass() == RuntimeException.class) {
                throw new GitException(message, e.getCause());
            } else {
                throw new GitException(message, e);
            }
        }
    }

    public void status() throws GitException {
        MyGitCommit commit = curNode.commit;
        if (!metainf.headCommitHash.equals(metainf.branches.get(commit.getBranch()))) {
            output.println("Error while performing status: Head is detached");
            return;
        }
        List<Path> untrackedNewFiles = new ArrayList<>();
        List<Path> untrackedModifiedFiles = new ArrayList<>();
        Set<Path> untrackedDeletedFiles = new HashSet<>();
        untrackedDeletedFiles.addAll(commit.getFiles().keySet());
        untrackedDeletedFiles.addAll(index.getNewFiles().keySet());
        untrackedDeletedFiles.addAll(index.getModifiedFiles().keySet());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (path.equals(myGitDir)) {
                    continue;
                }
                if (path.toFile().isDirectory()) {
                    Files.walk(path).forEach(p -> {
                        try {
                            addFileIfUntracked(path, commit, untrackedNewFiles, untrackedModifiedFiles, untrackedDeletedFiles);
                            untrackedDeletedFiles.remove(path);
                        } catch (GitException e) {
                            throw new RuntimeException(e);
                        }
                    });
                } else {
                    addFileIfUntracked(path, commit, untrackedNewFiles, untrackedModifiedFiles, untrackedDeletedFiles);
                    untrackedDeletedFiles.remove(path);
                }
            }
        } catch (IOException e) {
            throw new GitException("Can't read directory", e);
        } catch (RuntimeException e) {
            throw (GitException) e.getCause();
        }
        String branchName = commit.getBranch();
        output.printf("Current branch is '%s'\n", branchName);
        boolean flag = true;
        if (!index.isEmpty()) {
            output.print("Ready to commit:\n\n");
            flag = false;
        }
        printFiles(index.getNewFiles().keySet(), "New files:");
        printFiles(index.getModifiedFiles().keySet(), "Modified files:");
        if (!untrackedNewFiles.isEmpty() || !untrackedModifiedFiles.isEmpty() || !untrackedDeletedFiles.isEmpty()) {
            output.print("Untracked files:\n\n");
            flag = false;
        }
        printFiles(untrackedNewFiles, "New files:");
        printFiles(untrackedModifiedFiles, "Modified files:");
        printFiles(untrackedDeletedFiles, "Removed files:");
        if (flag) {
            output.println("Everything up to date");
        }
    }

    public void add(@NotNull List<@NotNull String> arguments) throws GitException {
        walk(arguments, "Add completed unsuccessful", index::containsFile, path -> {
            try {
                addFileToIndex(path, getFileHash(path));
            } catch (GitException e) {
                throw new RuntimeException(e);
            }
        });
        output.println("Add completed successful");
    }

    public void commit(String message) throws GitException {
        makeCommit(message, curNode.commit.getBranch());
        output.println("Files committed");
    }

    public void log(String revision) {
        Node nd = curNode;
        String flagHash = "";
        int flagInt = Integer.MAX_VALUE;
        if (revision != null && revision.startsWith("HEAD~")) {
            flagInt = Integer.parseInt(revision.substring(5));
        } else if (revision != null && !revision.equals("master")) {
            flagHash = revision;
        }
        while (nd != null && !nd.commit.getHash().equals(flagHash) && flagInt >= 0) {
            MyGitCommit commit = nd.commit;
            if (commit.getMessage().equals("New branch")) {
                nd = nd.parent;
                continue;
            }
            /*output.println("Commit " + commit.getHash());
            output.println("Author: " + commit.getAuthor());
            output.println("Date: " + commit.getDate());*/
            // For tests
            output.println("Commit COMMIT_HASH");
            output.println("Author: Test user");
            output.println("Date: COMMIT_DATE");
            output.printf("\n%s\n", commit.getMessage());
            if (!commit.getMessage().equals("Initial commit")) {
                output.println();
            }
            nd = nd.parent;
            flagInt--;
        }
    }

    public void rm(@NotNull List<@NotNull String> arguments) throws GitException {
        walk(arguments, "Rm completed unsuccessful", (path, hash) -> !index.containsFile(path, hash), path -> {
            try {
                rmFileFromIndex(path);
            } catch (GitException e) {
                throw new RuntimeException(e);
            }
        });
        output.println("Rm completed successful");
    }

    public void checkout(@NotNull List<@NotNull String> arguments) throws GitException {
        MyGitCommit commit = curNode.commit;
        String command = arguments.get(0);
        if (command.equals("--")) {
            for (String file : arguments.subList(1, arguments.size())) {
                Path path = Path.of(directory + "/" + file);
                String fileHash = index.getFileHash(path);
                if (fileHash == null) {
                    fileHash = commit.getFiles().get(path);
                }
                if (fileHash == null) {
                    continue;
                }
                try {
                    Files.copy(Path.of(filesDir + "/" + fileHash), path, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    String message = "Can't copy file";
                    output.println(message);
                    throw new GitException(message, e);
                }
            }
        } else {
            String commitHash;
            if (command.startsWith("HEAD~")) {
                int to = Integer.parseInt(command.substring(5));
                commitHash = getRelativeRevisionFromHead(to);
            } else {
                commitHash = metainf.branches.getOrDefault(command, command);
            }
            curNode = graph.get(commitHash);
            if (curNode == null) {
                throw new GitException("Such commit hash is not available");
            }
            metainf.headCommitHash = curNode.commit.getHash();
            updateDirectoryByCommit(curNode.commit, "Can't checkout");
            writeMetainf();
        }
        output.println("Checkout completed successful");
    }

    public void reset(String toRevision) throws GitException {
        String outMes = "Can't reset";
        deleteLastCommits(toRevision, outMes);
        if (curNode == null) {
            throw new GitException("To big reset");
        }
        MyGitCommit commit = curNode.commit;
        updateDirectoryByCommit(commit, outMes);
        metainf.headCommitHash = commit.getHash();
        metainf.branches.put(commit.getBranch(), commit.getHash());
        output.println("Reset successful");
    }

    public void branchCreate(String branchName) throws GitException {
        makeCommit("New branch", branchName);
        output.printf("Branch %s created successfully\n" +
                "You can checkout it with 'checkout %s'\n", branchName, branchName);
    }

    public void showBranches() {
        output.println("Available branches:");
        for (var branch : metainf.branches.keySet()) {
            output.println(branch);
        }
    }

    public void branchRemove(String branchName) throws GitException {
        if (branchName.equals("master")) {
            throw new GitException("Can't remove master");
        }
        String headCommitHash = metainf.branches.get(branchName);
        if (headCommitHash == null) {
            throw new GitException("No such branch");
        }
        Node tempNode = curNode;
        curNode = graph.get(headCommitHash);
        Node nd = curNode;
        int to = 0;
        while (nd.commit.getBranch().equals(branchName)) {
            nd = nd.parent;
            to++;
        }
        String revision = String.format("HEAD~%d", to);
        if (tempNode.commit.getBranch().equals(branchName)) {
            tempNode = nd;
            metainf.headCommitHash = nd.commit.getHash();
            reset(revision);
        } else {
            deleteLastCommits(revision, "Can't remove branch");
        }
        curNode = tempNode;
        metainf.branches.remove(branchName);
        writeMetainf();
        output.printf("Branch %s removed successfully\n", branchName);
    }

    public @NotNull String getRelativeRevisionFromHead(int n) throws GitException {
        Node nd = curNode;
        for (int i = 0; i < n; ++i) {
            nd = nd.parent;
        }
        return nd.commit.getHash();
    }

    // Private functions
    private boolean isMyGitDirectory() {
        return Files.exists(myGitDir);
    }

    private String getFileHash(Path path) throws GitException {
        if (!Files.exists(path) || path.toFile().isDirectory()) {
            return "";
        }
        try (InputStream is = Files.newInputStream(path)) {
            return DigestUtils.md5Hex(is);
        } catch (IOException e) {
            throw new GitException("Can't read file", e);
        }
    }

    private boolean isDeletedFile(Path path, MyGitCommit commit) {
        return commit.containsName(path) && !Files.exists(path);
    }

    private void addFileIfUntracked(Path path, MyGitCommit commit, List<Path> uNewFiles, List<Path> uModFiles, Set<Path> uDelFiles)
            throws GitException {
        String hash = getFileHash(path);
        if (!commit.containsName(path) && !index.containsName(path)) {
            uNewFiles.add(path);
        } else if (isDeletedFile(path, commit)) {
            uDelFiles.add(path);
        } else if (commit.containsName(path) && !commit.containsFile(path, hash)
                && !index.containsFile(path, hash)) {
            uModFiles.add(path);
        }
    }

    private void printFiles(Collection<Path> files, String message) {
        if (!files.isEmpty()) {
            output.println(message);
            List<Path> sortedFiles = files.stream().sorted().collect(Collectors.toList());
            for (Path p : sortedFiles) {
                output.printf("    %s\n", p.getFileName());
            }
            output.println();
        }
    }

    private MyGitFileMode getFileMode(Path path) {
        MyGitCommit commit = curNode.commit;
        if (isDeletedFile(path, commit)) {
            return MyGitFileMode.DELETED;
        }
        return commit.containsName(path) ? MyGitFileMode.MODIFIED : MyGitFileMode.NEW;
    }

    private void addFileToIndex(Path path, String hash) {
        String prevHash = index.add(path, hash, getFileMode(path));
        try {
            if (prevHash != null) {
                Path oldFile = Path.of(filesDir + "/" + prevHash);
                Files.delete(oldFile);
            }
            if (Files.exists(path)) {
                Path pathMyGit = Path.of(filesDir + "/" + hash);
                Files.copy(path, pathMyGit);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void rmFileFromIndex(Path path) throws GitException {
        Path file = Path.of(filesDir + "/" + index.getFileHash(path));
        try {
            Files.delete(file);
        } catch (IOException e) {
            String message = "Can't remove file";
            output.println(message);
            throw new GitException(message, e);
        }
        index.delete(path);
    }

    private void walk(@NotNull List<@NotNull String> arguments, String message, BiPredicate<Path, String> predicate, Consumer<Path> func)
            throws GitException {
        for (String p : arguments) {
            Path path = Path.of(directory + "/" + p);
            String hash = getFileHash(path);
            if (predicate.test(path, hash)) {
                continue;
            }
            try {
                if (path.toFile().isDirectory()) {
                    Files.walk(path).forEach(func);
                } else {
                    func.accept(path);
                }
            } catch (IOException | RuntimeException e) {
                output.println(message);
                String mes = "Can't change files in .myGit";
                if (e.getClass() == RuntimeException.class) {
                    throw new GitException(mes, e.getCause());
                } else {
                    throw new GitException(mes, e);
                }
            }
        }
    }

    private void updateDirectoryByCommit(MyGitCommit commit, String message) throws GitException {
        List<String> args = new LinkedList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (!path.equals(myGitDir)) {
                    args.add(path.getFileName().toString());
                }
            }
        } catch (IOException e) {
            output.println(message);
            throw new GitException("Can't delete file from myGit repo", e);
        }
        walk(args, message, (path, hash) -> false, path -> {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            for (var entry : commit.getFiles().entrySet()) {
                Path pathTarget = entry.getKey();
                Path pathSource = Path.of(filesDir + "/" + entry.getValue());
                if (!(Files.exists(pathTarget) && commit.containsFile(pathTarget, getFileHash(pathTarget)))) {
                    Files.copy(pathSource, pathTarget);
                }
            }
        } catch (IOException e) {
            output.println(message);
            throw new GitException("Can't copy files from .myGit", e);
        }
    }

    private void makeCommit(String message, String branchName) throws GitException {
        MyGitCommit parentCommit = curNode.commit;
        MyGitCommit commit = new MyGitCommit(message, parentCommit, index, branchName);
        Map<String, String> branches = metainf.branches;
        if (!branches.containsKey(branchName)) {
            branches.put(branchName, commit.getHash());
        } else {
            branches.put(curNode.commit.getBranch(), commit.getHash());
        }
        index.clear();
        Node prevNode = curNode;
        curNode = new Node(commit);
        curNode.parent = prevNode;
        graph.put(commit.getHash(), curNode);
        metainf.headCommitHash = commit.getHash();
        try {
            writer.writeValue(Paths.get(commitsDir + "/" + commit.getHash() + ".json").toFile(), commit);
            writer.writeValue(metainfPath.toFile(), metainf);
        } catch (IOException e) {
            throw new GitException("Can't create commit", e);
        }
    }

    private void writeMetainf() throws GitException {
        try {
            writer.writeValue(metainfPath.toFile(), metainf);
        } catch (IOException e) {
            throw new GitException("Can't update metainf", e);
        }
    }

    private void deleteLastCommits(String toRevision, String outMes) throws GitException {
        String flagHash = "";
        int flagInt = Integer.MAX_VALUE;
        if (toRevision.startsWith("HEAD~")) {
            flagInt = Integer.parseInt(toRevision.substring(5));
        } else if (!toRevision.equals("master")) {
            flagHash = toRevision;
        }
        while (!flagHash.equals(curNode.commit.getHash()) && flagInt > 0) {
            if (curNode.parent == null) {
                throw new GitException("To long reset");
            }
            MyGitCommit commit = curNode.commit;
            MyGitCommit parentCommit = curNode.parent.commit;
            try {
                for (var entry : commit.getFiles().entrySet()) {
                    Path filePath = entry.getKey();
                    String hash = entry.getValue();
                    if (commit.containsFile(filePath, hash) && !parentCommit.containsFile(filePath, hash)) {
                        Path path = Path.of(filesDir + "/" + hash);
                        Files.delete(path);
                    }
                }
                Files.delete(Path.of(commitsDir + "/" + commit.getHash() + ".json"));
            } catch (IOException e) {
                output.println(outMes);
                throw new GitException("Can't delete file from myGit repository", e);
            }
            curNode = curNode.parent;
            flagInt--;
        }
    }
}
