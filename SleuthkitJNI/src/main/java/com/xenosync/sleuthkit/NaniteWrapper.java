package com.xenosync.sleuthkit;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.tika.mime.MediaType;
import uk.bl.wap.nanite.droid.Nanite;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.signature.SignatureFileException;

import java.io.File;
import java.io.IOException;

public class NaniteWrapper {
    NaniteWrapper(File file, String md5) throws SignatureFileException, SignatureParseException, ConfigurationException, IOException {
        //System.out.println(file.getAbsolutePath());
        new Nanite(file.getAbsolutePath());
    }
}
