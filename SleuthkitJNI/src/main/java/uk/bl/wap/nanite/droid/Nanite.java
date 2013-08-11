/**
 * 
 */
package uk.bl.wap.nanite.droid;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import javax.activation.MimeTypeParseException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.signature.SignatureFileException;
import uk.gov.nationalarchives.droid.core.interfaces.signature.SignatureManagerException;

/**
 * 
 
 */
public class Nanite {

	DroidBinarySignatureDetector nan = null;

    public Nanite(){

    }

	public Nanite(String absolutePath) throws IOException, SignatureFileException, SignatureParseException, ConfigurationException {
		File file = new File(absolutePath);
        nan = new DroidBinarySignatureDetector();
        System.out.println("File: "+ file.getName());
        System.out.println("Nanite using DROID binary signature file version "+nan.getBinarySigFileVersion());
        System.out.println("Result: " + nan.getMimeType(file));
        System.out.println("----");

	}

    public MediaType getMediaType(String absolutePath) throws ConfigurationException, SignatureParseException, SignatureFileException, IOException {
        File file = new File(absolutePath);
        nan = new DroidBinarySignatureDetector();
        Metadata metadata = new Metadata();
        metadata.set(Metadata.RESOURCE_NAME_KEY, file.toURI().toString());
        return nan.detect(new FileInputStream(file), metadata);
    }

	/**
	 * @param args
	 * @throws IOException 
	 * @throws SignatureManagerException 
	 * @throws ConfigurationException 
	 * @throws SignatureFileException 
	 * @throws MimeTypeParseException 
	 * @throws SignatureParseException 
	 */
	public static void main(String[] args) throws IOException, SignatureManagerException, ConfigurationException, SignatureFileException, MimeTypeParseException, SignatureParseException {
		DroidBinarySignatureDetector nan = new DroidBinarySignatureDetector();
		for( String fname : args ) {
			File file = new File(fname);
			System.out.println("File: "+fname);
			System.out.println("Nanite using DROID binary signature file version "+nan.getBinarySigFileVersion());
			System.out.println("Result: " + nan.getMimeType(file));
			System.out.println("----");
		}
	}

	/**
	 * @param payload
	 * @return
	 */
	public String identify(byte[] payload) {
		return nan.identify(payload);
	}	

}
