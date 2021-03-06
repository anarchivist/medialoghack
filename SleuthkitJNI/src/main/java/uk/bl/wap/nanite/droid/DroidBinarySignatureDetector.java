/**
 * 
 */
package uk.bl.wap.nanite.droid;

import static uk.gov.nationalarchives.droid.core.interfaces.config.RuntimeConfig.DROID_USER;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;

import uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResult;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResultCollection;
import uk.gov.nationalarchives.droid.core.interfaces.RequestIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.config.DroidGlobalConfig;
import uk.gov.nationalarchives.droid.core.interfaces.resource.FileSystemIdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.resource.RequestMetaData;
import uk.gov.nationalarchives.droid.core.interfaces.signature.SignatureFileException;
import uk.gov.nationalarchives.droid.core.interfaces.signature.SignatureManagerException;
import uk.gov.nationalarchives.droid.core.interfaces.signature.SignatureType;
import uk.gov.nationalarchives.droid.signature.SignatureManagerImpl;

/**
* Finding the actual droid-core invocation was tricky
 * From droid command line
 * - ReportCommand which launches a profileWalker,
 * - which fires a FileEventHandler when it hits a file,
 * - which submits an Identification request to the AsyncDroid subtype SubmissionGateway, 
 * - which calls DroidCore,
 * - which calls uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier
 * - Following which, SubmissionGateway does some handleContainer stuff, 
 * executes the container matching engine and does some complex logic to resolve the result.
 * 
 * This is all further complicated by the way a mix of Spring and Java is used to initialize
 * things, which makes partial or fast initialization rather difficult.
 * 
 * For droid-command-line, the stringing together starts with:
 * /droid-command-line/src/main/resources/META-INF/ui-spring.xml
 * this sets up the ProfileContextLocator and the SpringProfileInstanceFactory.
 * Other parts of the code set up Profiles and eventually call:
 * uk.gov.nationalarchives.droid.profile.ProfileContextLocator.openProfileInstanceManager(ProfileInstance)
 * which calls
 * uk.gov.nationalarchives.droid.profile.SpringProfileInstanceFactory.getProfileInstanceManager(ProfileInstance, Properties)
 * which then injects more xml, including:
 * @see /droid-results/src/main/resources/META-INF/spring-results.xml
 * which sets up most of the SubmissionGateway and identification stuff
 * (including the BinarySignatureIdentifier and the Container identifiers).
 * 
 * The ui-spring.xml file also includes
 * /droid-results/src/main/resources/META-INF/spring-signature.xml
 * which sets up the pronomClient for downloading binary and container signatures.
 * 
 * So, the profile stuff is hooked into the DB stuff which is hooked into the identifiers.
 * Everything is tightly coupled, so teasing it apart is hard work.
 * 
 * @see uk.gov.nationalarchives.droid.submitter.SubmissionGateway (in droid-results)
 * @see uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier
 * @see uk.gov.nationalarchives.droid.submitter.FileEventHandler.onEvent(File, ResourceId, ResourceId)
 * 
 * Also found 
 * @see uk.gov.nationalarchives.droid.command.action.DownloadSignatureUpdateCommand
 * which indicates how to download the latest sig file, 
 * but perhaps the SignatureManagerImpl does all that is needed?
 * 
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 * @author Fabian Steeg
 * @author <a href="mailto:carl.wilson@bl.uk">Carl Wilson</a> <a
 *         href="http://sourceforge.net/users/carlwilson-bl"
 *         >carlwilson-bl@SourceForge</a> <a
 *         href="https://github.com/carlwilson-bl">carlwilson-bl@github</a>
 * *
 */
public class DroidBinarySignatureDetector extends Object implements Detector {

	/** */
	private static final long serialVersionUID = -8969164208391105690L;
	
	private static Logger log = Logger.getLogger(Nanite.class.getName());
	private File tmpFile = null;

	
	private BinarySignatureIdentifier bsi;
	//private SignatureManager sm;
	//private ClassPathXmlApplicationContext context;
	//private SubmissionGateway sg;
	
	/* --- --- --- */
	
	@Override
	public MediaType detect(InputStream input, Metadata metadata)
			throws IOException {
		String uri = metadata.get(Metadata.RESOURCE_NAME_KEY);
		IdentificationRequest ir = createInputStreamIdentificationRequest(URI.create(uri), input );		

		IdentificationResultCollection resultCollection = this.identify(ir);
		return getMimeTypeFromResults(resultCollection.getResults());
	}

	/**
	 * The default DroidGlobalConfig hardcodes V45 as the default SigFile version.
	 * (Note that there is a FIXME indicating that they wish to change this to be the latest version).
	 * 
	 * I've sub-classed that class so that the defaults can be overridden, and the latest Sig. File can be used.
	 * 
	 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
	 */
	private class NaniteGlobalConfig extends DroidGlobalConfig {

	    /**
	     * Extend the constructor to ensure we set add the desired sig file.
	     * @throws IOException
	     */
	    public NaniteGlobalConfig() throws IOException {
			super();
	        createResourceFile(getSignatureFileDir(), 
	        		DroidDetector.DROID_SIGNATURE_FILE, 
	        		DroidDetector.DROID_SIG_RESOURCE, true);
		}
		
		/**
		 * Override init to set the default property version.
		 */
		public void init() throws ConfigurationException {
	        super.init();
	        this.getProperties().setProperty("profile.defaultBinarySigFileVersion", DroidDetector.DROID_SIGNATURE_FILE.replace(".xml", ""));
		}
		
		/**
		 * Copied this in from the parent class as it's a private method.
		 * 
		 * @param resourceDir
		 * @param fileName
		 * @param resourceName
		 * @throws IOException
		 */
		private void createResourceFile(File resourceDir, String fileName, String resourceName, boolean overwrite ) throws IOException {
	        InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName);
	        if (in == null) {
	        	log.warn("Resource not found: " + resourceName);
	        } else {
	            File resourcefile = new File(resourceDir, fileName);
	            if ( resourcefile.createNewFile() || overwrite ) {
	                OutputStream out = new FileOutputStream(resourcefile);
	                try {
	    	        	//log.debug("Copying "+resourceName+" to "+resourcefile.getAbsolutePath());
	                    IOUtils.copy(in, out);
	                } catch (Exception e ) {
	    	        	log.warn("Failed while copying "+resourceName+" to "+resourcefile.getAbsolutePath());
	                } finally {
	                    if (out != null) {
	                        out.close();
	                    }
	                    if (in != null) {
	                        in.close();
	                    }
	                }
	            }
	        }
	    }
	}


	public DroidBinarySignatureDetector() throws IOException, SignatureFileException, ConfigurationException, SignatureParseException {
		tmpFile = File.createTempFile("Nanite", "tmp");
		tmpFile.deleteOnExit();
		
		// Now set up DROID, logging and temp, etc.
		System.setProperty("consoleLogThreshold","INFO");
		System.setProperty("logFile", "./nanite.log");
		PropertyConfigurator.configure(this.getClass().getClassLoader().getResource("log4j.properties"));
		
		// System.getProperty("java.io.tmpdir")
		//String droidDirname = System.getProperty("user.home")+File.separator+".droid6";
		String droidDirname = System.getProperty("java.io.tmpdir")+File.separator+"droid6";
		//log.warn("GOT: "+droidDirname);
		File droidDir = new File(droidDirname);
		if( ! droidDir.isDirectory() ) {
			if( ! droidDir.exists() ) {
				droidDir.mkdirs();
			} else {
				throw new IOException("Cannot create droid folder: "+droidDirname);
			}
		}
		System.setProperty(DROID_USER, droidDir.getAbsolutePath());

		/*
	
		// Fire up required classes via Spring:
		context = new ClassPathXmlApplicationContext("classpath*:/META-INF/ui-spring.xml");
        context.registerShutdownHook();
		sm = (SignatureManager) context.getBean("signatureManager");
		//sg = (SubmissionGateway) context.getBean("submissionGateway");
		
        */
		
		// Without Spring, you can support basic usage using this:
		NaniteGlobalConfig dgc = new NaniteGlobalConfig();			
		dgc.init();
		SignatureManagerImpl sm = new SignatureManagerImpl();
		sm.setConfig(dgc);
		
/*		
 		// This was a further attempt to set up the SignatureManager manually instead of via Spring. Doesn't work very well.
		Map<SignatureType, SignatureUpdateService> signatureUpdateServices = new HashMap<SignatureType, SignatureUpdateService>();
		PronomSignatureService pss = new PronomSignatureService();
		pss.setFilenamePattern("DROID_SignatureFile_V%s.xml");
		PronomService pronomService = null;
		pss.setPronomService(pronomService);
		signatureUpdateServices.put(SignatureType.BINARY, pss);
		signatureUpdateServices.put(SignatureType.CONTAINER, new ContainerSignatureHttpService() );
		sm.setSignatureUpdateServices(signatureUpdateServices);
		sm.init();
*/
		
		// Now set up the Binary Signature Identifier with the right signature from the manager:
		bsi = new BinarySignatureIdentifier();

		/*
		// This downloads the latest version:
		try {
			bsi.setSignatureFile(sm.downloadLatest(SignatureType.BINARY).getFile().getAbsolutePath());
		} catch (SignatureManagerException e) {
			e.printStackTrace();
		}
		*/

		// This lists the available sig. files (no downloads):
        //for( String item : sm.getAvailableSignatureFiles().get(SignatureType.BINARY).keySet() ) {
        //	System.out.println("Key:"+item+" "+sm.getAvailableSignatureFiles().get(SignatureType.BINARY).get(item).getVersion());
        //}

		// This uses the cached default sig. file as specified by the GlobalConfig class:
		bsi.setSignatureFile(sm.getDefaultSignatures().get(SignatureType.BINARY).getFile().getAbsolutePath());
		
        // This uses a local file instead, but requires a path to a local file.
	    //bsi.setSignatureFile("C:/Users/AnJackson/workspace/nanite/nanite-droid/src/main/resources/DROID_SignatureFile_V55 - no EOF.xml");
	    //bsi.setSignatureFile("C:/Users/AnJackson/workspace/nanite/nanite-droid/src/main/resources/DROID_SignatureFile_V55.xml");

		// The sig. files is specified, so initialise the binary sig matcher:
		bsi.init();		
	}

	/**
	 * @return The version of the binary signature file that is in use.
	 */
	public int getBinarySigFileVersion() {
		String version = DroidDetector.DROID_SIGNATURE_FILE.replace("DROID_SignatureFile_V", "");
		version = version.replace(".xml", "");
		return Integer.parseInt(version);
	}

	/**
	 * 
	 * @param ir
	 * @return
	 */
	private IdentificationResultCollection identify(IdentificationRequest ir) {
		IdentificationResultCollection results = bsi.matchBinarySignatures(ir);
		// Strip out lower priority results
		bsi.removeLowerPriorityHits(results);
		return results;
		/*
		Future<IdentificationResultCollection> task = sg.submit(ir);
		while( ! task.isDone() ) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		try {
			return task.get();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
		*/
	}

	/**
	 * 
	 * @param payload
	 * @return
	 */
	String identify(byte[] payload) {
		String droidType = "application/octet-stream";
		try {
			IdentificationResultCollection irc = this.identify(
					DroidBinarySignatureDetector.createByteArrayIdentificationRequest(tmpFile.toURI(), payload) );
			/*
			IdentificationResultCollection ircf = nanite.identify(
					Nanite.createFileIdentificationRequest(tmpFile) );
			*/
			droidType = DroidBinarySignatureDetector.getMimeTypeFromResults(irc.getResults()).toString();
		} catch( Throwable e ) {
			log.error("Exception on DroidNanite invocation: "+e);
		}
		return droidType;
	}
	
	/**
	 * 
	 * @param file
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private static IdentificationRequest createFileIdentificationRequest( File file ) throws FileNotFoundException, IOException {
		URI uri = file.toURI();
        RequestMetaData metaData = new RequestMetaData( file.length(), file
                .lastModified(), file.getName());
        
        RequestIdentifier identifier = new RequestIdentifier(uri);
		identifier.setParentId(1L);
        //identifier.setParentResourceId(parentId);
        //identifier.setResourceId(nodeId);
        
        IdentificationRequest ir = new FileSystemIdentificationRequest(metaData, identifier);
        // Attach the byte arrays of content:
        ir.open(new FileInputStream(file));
		return ir;
	}
	
	private static IdentificationRequest createByteArrayIdentificationRequest( URI uri, byte[] data ) throws IOException {
        RequestMetaData metaData = new RequestMetaData( (long)data.length, null, uri.toString() );
        
        RequestIdentifier identifier = new RequestIdentifier(uri);
		identifier.setParentId(1L);
        //identifier.setParentResourceId(parentId);
        //identifier.setResourceId(nodeId);
        
        IdentificationRequest ir = new ByteArrayIdentificationRequest(metaData, identifier, data);
        // Attach the byte arrays of content:
        //ir.open(new ByteArrayInputStream(data));
		return ir;
	}


	/**
	 * 
	 * @param uri
	 * @param in
	 * @return
	 * @throws IOException
	 */
	private static IdentificationRequest createInputStreamIdentificationRequest( URI uri, InputStream in ) throws IOException {
        RequestMetaData metaData = new RequestMetaData( (long)in.available(), null, uri.toString() );
        
        RequestIdentifier identifier = new RequestIdentifier(uri);
		identifier.setParentId(1L);
        //identifier.setParentResourceId(parentId);
        //identifier.setResourceId(nodeId);
        
        IdentificationRequest ir = new InputStreamIdentificationRequest(metaData, identifier, in);
        // Attach the byte arrays of content:
        //ir.open(in);
		return ir;
	}
	
	/**
	 * TODO Choose 'vnd' Vendor-style MIME types over other options when there are many in each Result.
	 * TODO This does not cope ideally with multiple/degenerate Results. 
	 * e.g. old TIFF or current RTF that cannot tell the difference so reports no versions.
	 * If there are sigs that differ more than this, this will ignore the versions.
	 * 
	 * @param list
	 * @return
	 * @throws MimeTypeParseException 
	 */
	protected static MediaType getMimeTypeFromResults( List<IdentificationResult> results ) {
		if( results == null || results.size() == 0 ) return MediaType.OCTET_STREAM;
		// Get the first result:
		IdentificationResult r = results.get(0);
		// Sort out the MIME type mapping:
		String mimeType = null;
		String mimeTypeString = r.getMimeType();
		if( mimeTypeString != null ) {
			// This sometimes has ", " separated multiple types
			String[] mimeTypeList = mimeTypeString.split(", ");
			// Taking first (highest priority) MIME type:
			mimeType = mimeTypeList[0];
		}
		// Build a MediaType
		MediaType mediaType = MediaType.parse(mimeType);
		Map<String,String> parameters = null;
		// Is there a MIME Type?
		if( mimeType != null && ! "".equals(mimeType) ) {
			parameters = new HashMap<String,String>(mediaType.getParameters());
			// Patch on a version parameter if there isn't one there already:
			if( parameters.get("version") == null && 
					r.getVersion() != null && (! "".equals(r.getVersion())) &&
					// But ONLY if there is ONLY one result.
					results.size() == 1 ) {
				parameters.put("version", r.getVersion());
                parameters.put("puid", r.getPuid().replace("/", "-"));
			}
		} else {
			parameters = new HashMap<String,String>();
			// If there isn't a MIME type, make one up:
			String id = "puid-"+r.getPuid().replace("/", "-");
			String name = r.getName().replace("\"","'");
			// Lead with the PUID:
			mediaType = MediaType.parse("application/x-"+id);
			parameters.put("name", name);
			// Add the version, if set:
			String version = r.getVersion();
			if( version != null && !"".equals(version) && !"null".equals(version) ) {
				parameters.put("version", version);
			}
		}
		
		return new MediaType(mediaType,parameters);
	}
	
	/**
	 * 
	 * @param result
	 * @return
	 */
	public static MediaType getMimeTypeFromResults(IdentificationResult result) {
		List<IdentificationResult> list = new ArrayList<IdentificationResult>();
		list.add(result);
		return getMimeTypeFromResults(list);
	}
	
	
	String getMimeType( File file ) throws FileNotFoundException, IOException, ConfigurationException, SignatureFileException {
		//IdentificationRequest ir = createFileIdentificationRequest(file);
		
		//byte[] data =  org.apache.commons.io.FileUtils.readFileToByteArray(file);
		//IdentificationRequest ir = createByteArrayIdentificationRequest(file.toURI(), data);		
		Metadata metadata = new Metadata();
		metadata.set(Metadata.RESOURCE_NAME_KEY, file.toURI().toString());
		return this.detect(new FileInputStream(file), metadata).toString();
	}

    public String getMimeType(InputStream is, String localPath) throws FileNotFoundException, IOException, ConfigurationException, SignatureFileException {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.RESOURCE_NAME_KEY, localPath);
        return this.detect(is, metadata).toString();
    }

	/* (non-Javadoc)
	 * @see java.lang.Object#finalize()
	 */
	@Override
	protected void finalize() throws Throwable {
		super.finalize();
		if( tmpFile.exists() ) {
			tmpFile.delete();
		}
	}

}
