package com.xenosync.sleuthkit;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.IOUtils;
import org.apache.tika.Tika;
import org.sleuthkit.datamodel.*;
import org.sleuthkit.datamodel.File;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.signature.SignatureFileException;

import java.io.*;
import java.util.List;

public class FileLister {
    private Image currentImage;
    private Tika tika = new Tika();

    FileLister(String db) throws TskCoreException, IOException, SignatureParseException, SignatureFileException, ConfigurationException, InterruptedException {

        SleuthkitCase sleuthkitCase = SleuthkitCase.openCase(db);

        List<Image> images = sleuthkitCase.getImages();
        for(Image image: images){
            currentImage = image;
            System.out.println(image.getName());
            if(image.hasChildren()){
                for(Content content: image.getChildren()){
                    recurseContent(content);
                }
            }
        }
    }

    private void recurseContent(Content content) throws TskCoreException, IOException, SignatureParseException, SignatureFileException, ConfigurationException, InterruptedException {
        if(content instanceof File){
            File file =  (File) content;

            //copy the bytestream from image to tmp file
            InputStream is = new BufferedInputStream(new ReadContentInputStream(file));
            FileOutputStream fos = new FileOutputStream("/tmp/" +  file.getName());
            IOUtils.copy(is, fos);
            is.close();
            fos.close();

            java.io.File tmpFile = new java.io.File("/tmp", file.getName());
            if(tmpFile.exists() && DigestUtils.md5Hex(new FileInputStream(tmpFile)).equals(file.getMd5Hash())){
                System.out.println("  " + file.getName());
                System.out.println("\tTIKA: " + tika.detect(new BufferedInputStream(new ReadContentInputStream(file))));
                //System.out.print("DROID: ");
                //NaniteWrapper nanite = new NaniteWrapper(tmpFile, file.getMd5Hash());
                String detect = fido(tmpFile);
                if(detect.substring(0,2).equals("OK")){
                    String[] mime = detect.split(",");
                    System.out.println("\tFIDO: " + mime[7].substring(1, mime[7].length() - 1) + " " + mime[8]);
                }
            } else {

                System.err.println("checksum in image does not match");
            }

            tmpFile.delete();


        }

        if(content.hasChildren()){
            for(Content child: content.getChildren()){
                recurseContent(child);
            }
        }
    }

    private String fido(java.io.File file) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        Process process = Runtime.getRuntime().exec("/usr/local/fido/fido.py " + file.getAbsolutePath());
        process.waitFor();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(process.getInputStream())));
        String line;
        while((line = reader.readLine()) != null){
            sb.append(line);


        }
        reader.close();
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        new FileLister("src/main/resources/M1126.db");
    }
}
