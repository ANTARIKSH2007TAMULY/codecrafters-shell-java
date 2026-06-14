import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine();
            List<String> parts = parseInput(input);
            if (parts.isEmpty()) {
                continue;
            }
            String command = parts.get(0);
            if (command.equals("exit")) {
                break;
            } else if (command.equals("echo")) {
                System.out.println(String.join(" ", parts.subList(1, parts.size())));
            } else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else if (command.equals("cd")) {
                String dir = parts.get(1);
                if (dir.startsWith("~")) {
                    dir = System.getenv("HOME") + dir.substring(1);
                }
                Path path = Paths.get(System.getProperty("user.dir")).resolve(dir).normalize();
                if (Files.isDirectory(path)) {
                    System.setProperty("user.dir", path.toString());
                } else {
                    System.out.println("cd: " + dir + ": No such file or directory");
                }
            } else if (command.equals("type")) {
                String cmd = parts.get(1);
                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("cd")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String foundPath = findExecutable(cmd);
                    if (foundPath != null) {
                        System.out.println(cmd + " is " + foundPath);
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
            } else {
                if (findExecutable(command) != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.inheritIO();
                    pb.start().waitFor();
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static List<String> parseInput(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuotes) {
                if (c == '"') {
                    inDoubleQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '\\') {
                if (i + 1 < input.length()) {
                    i++;
                    current.append(input.charAt(i));
                }
            } else if (c == '\'') {
                inSingleQuotes = true;
            } else if (c == '"') {
                inDoubleQuotes = true;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path filePath = Paths.get(dir, command);
            if (Files.exists(filePath) && Files.isExecutable(filePath)) {
                return filePath.toString();
            }
        }
        return null;
    }
}
