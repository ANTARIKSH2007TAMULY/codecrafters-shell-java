import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine();
            if (input.equals("exit")) {
                break;
            } else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } else if (input.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else if (input.startsWith("cd ")) {
                String dir = input.substring(3);
                Path path = Paths.get(dir);
                if (Files.isDirectory(path)) {
                    System.setProperty("user.dir", path.toAbsolutePath().toString());
                } else {
                    System.out.println("cd: " + dir + ": No such file or directory");
                }
            } else if (input.startsWith("type ")) {
                String command = input.substring(5);
                if (command.equals("echo") || command.equals("exit") || command.equals("type") || command.equals("pwd") || command.equals("cd")) {
                    System.out.println(command + " is a shell builtin");
                } else {
                    String foundPath = findExecutable(command);
                    if (foundPath != null) {
                        System.out.println(command + " is " + foundPath);
                    } else {
                        System.out.println(command + ": not found");
                    }
                }
            } else {
                String[] parts = input.split(" ");
                String command = parts[0];
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
