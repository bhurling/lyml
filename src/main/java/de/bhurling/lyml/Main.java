package de.bhurling.lyml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import org.apache.commons.cli.*;

public class Main {
    private static String USAGE =
            "java -jar lyml.jar <api-token> [<api-token>...]";

    public static void main(String[] args) throws Exception {
        String fileName = "/truststore.jks"; // java does not trust the COMODO certificate for some reason

        InputStream in = Main.class.getResourceAsStream(fileName);

        final File tempFile = File.createTempFile("tmp", "jks");
        tempFile.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int len = in.read(buffer);
            while (len != -1) {
                out.write(buffer, 0, len);
                len = in.read(buffer);
            }
        }

        String path = tempFile.toString();
        System.setProperty("javax.net.ssl.trustStore", path); // so we use a custom keystore

        // parse command line options
        Options options = new Options();
        options.addOption("h", "help", false, "print this help");
        options.addOption(Option.builder("e").longOpt("export").desc("comma separated list of target export platforms (iOS, Android, Windows, Java)").hasArg().build());

        // default export options
        boolean android = true;
        boolean ios = true;
        boolean windows = true;
        boolean java = true;

        try {
            CommandLineParser commandLineParser = new DefaultParser();
            CommandLine line = commandLineParser.parse(options, args);

            // show help
            if (line.getArgs().length == 0 || line.hasOption("h")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp(USAGE, "", options, "", true);
                System.exit(1);
            }

            // specific export options
            if (line.hasOption("e")) {
                // reset all export options
                android = false;
                ios = false;
                windows = false;
                java = false;

                String[] exportPlatforms = line.getOptionValue("e").split(",");

                for (String p: exportPlatforms) {
                    if (p.equalsIgnoreCase("android")) {
                        android = true;
                    } else if (p.equalsIgnoreCase("ios")) {
                        ios = true;
                    } else if (p.equalsIgnoreCase("windows")) {
                        windows = true;
                    } else if (p.equalsIgnoreCase("java")) {
                        java = true;
                    } else {
                        System.out.println("Unknown export platform " + p + " - skipping");
                    }
                }
            }

            if (!(android || ios || windows || java)) {
                System.out.println("No target export platforms.");
                System.exit(1);
            }

            YmlParser parser = new YmlParser(line.getArgs());
            parser.fetchFromLocaleApp();
            parser.createResources(android, ios, windows, java);
        } catch (ParseException ignored) {}
    }
}