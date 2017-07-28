package org.fagu.ebooks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.fagu.ebooks.io.UnclosedInputStream;
import org.fagu.fmv.soft._7z._7z;
import org.fagu.fmv.utils.file.FileUtils;


/**
 * @author fagu
 */
public class EBooksFile {

	private final File file;

	private final Map<String, String> metadataMap;

	/**
	 * @param file
	 * @param metadataMap
	 */
	private EBooksFile(File file, Map<String, String> metadataMap) {
		this.file = file;
		this.metadataMap = metadataMap;
	}

	/**
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public static EBooksFile open(File file) throws IOException {
		EBooksFile eBooksFile = openWithJava(file);
		if(eBooksFile != null) {
			return eBooksFile;
		}
		// try with 7zip
		File epub7z = rewriteWith7Zip(file);
		file.delete();
		epub7z.renameTo(file);
		eBooksFile = openWithJava(file);
		if(eBooksFile != null) {
			return eBooksFile;
		}

		throw new RuntimeException("OPF file not found in " + file);
	}

	/**
	 * @return
	 */
	public File getFile() {
		return file;
	}

	/**
	 * @param name
	 * @return
	 */
	public String getMetadata(String name) {
		return metadataMap.get(name);
	}

	/**
	 * @return
	 */
	public String getAuthor() {
		return metadataMap.get("creator");
	}

	/**
	 * @return
	 */
	public String getTitle() {
		return metadataMap.get("title");
	}

	/**
	 * @param map
	 * @return
	 */
	public boolean needToWriteMetadatas(Map<String, String> map) {
		for(Entry<String, String> entry : map.entrySet()) {
			String expected = StringUtils.defaultString(entry.getValue());
			String current = StringUtils.defaultString(metadataMap.get(entry.getKey()));
			if( ! expected.equals(current) && ! "".equals(current)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param metadataMap
	 * @return
	 * @throws IOException
	 */
	public File writeMetadatas(Map<String, String> metadataMap) throws IOException {
		String fileName = file.getName();

		File outFile = File.createTempFile(FilenameUtils.getBaseName(fileName), '.' + FilenameUtils.getExtension(fileName), file.getParentFile());
		try (ZipArchiveInputStream zipInputStream = new ZipArchiveInputStream(new FileInputStream(file));
				ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
			ZipArchiveEntry zipEntry = null;
			while((zipEntry = zipInputStream.getNextZipEntry()) != null) {
				// System.out.println(zipEntry.getName());

				ZipEntry newZipEntry = new ZipEntry(zipEntry.getName());
				newZipEntry.setTime(zipEntry.getTime());
				zipOutputStream.putNextEntry(newZipEntry);

				if(zipEntry.getName().endsWith(".opf")) {
					byte[] opfData = overwriteMetadata(new UnclosedInputStream(zipInputStream), metadataMap);
					zipEntry.setSize(opfData.length);
					try (ByteArrayInputStream bais = new ByteArrayInputStream(opfData)) {
						IOUtils.copyLarge(bais, zipOutputStream);
					}
				} else {
					IOUtils.copyLarge(zipInputStream, zipOutputStream);
				}
				zipOutputStream.closeEntry();
			}
		}
		return outFile;
	}

	// **************************************************

	/**
	 * @param file
	 * @return
	 * @throws IOException
	 */
	private static EBooksFile openWithJava(File file) throws IOException {
		try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
			ZipEntry zipEntry = null;
			while((zipEntry = zipInputStream.getNextEntry()) != null) {
				// System.out.println(zipEntry.getName());
				if(zipEntry.getName().endsWith(".opf")) {
					SAXReader reader = new SAXReader();
					Document document = reader.read(zipInputStream);
					Element rootElement = document.getRootElement();
					Element metadataElement = rootElement.element("metadata");

					@SuppressWarnings("unchecked")
					List<Element> elements = metadataElement.elements();
					Map<String, String> metadataMap = new HashMap<>();
					for(Element element : elements) {
						String name = element.getName();
						if( ! "meta".equals(name)) {
							metadataMap.put(name, element.getText());
						}
					}

					return new EBooksFile(file, metadataMap);
				} else {
					IOUtils.copyLarge(zipInputStream, NullOutputStream.NULL_OUTPUT_STREAM);
				}
			}
		} catch(DocumentException e) {
			throw new IOException(e);
		}
		return null;
	}

	/**
	 * @param file
	 * @return
	 * @throws IOException
	 */
	private static File rewriteWith7Zip(File file) throws IOException {
		File tmpFolder = FileUtils.getTempFolder("ebook-", "-tmp");
		File outFile = File.createTempFile("ebook-", ".epub");

		try {
			_7z.extract(file, tmpFolder);

			try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
				for(File f : tmpFolder.listFiles()) {
					addToZip(f, zipOutputStream, null);
				}
			}
		} finally {
			org.apache.commons.io.FileUtils.deleteDirectory(tmpFolder);
		}
		return outFile;
	}

	/**
	 * @param file
	 * @param zipOutputStream
	 * @param path
	 * @throws IOException
	 */
	private static void addToZip(File file, ZipOutputStream zipOutputStream, String path) throws IOException {
		File[] files = file.listFiles();
		if(files == null || files.length == 0) {
			return;
		}
		String prefix = path != null ? path + '/' : "";
		for(File f : files) {
			if(f.isDirectory()) {
				addToZip(f, zipOutputStream, prefix + f.getName());
				continue;
			}
			ZipEntry zipEntry = new ZipEntry(prefix + f.getName());
			zipEntry.setSize(f.length());
			zipOutputStream.putNextEntry(zipEntry);
			org.apache.commons.io.FileUtils.copyFile(f, zipOutputStream);
			zipOutputStream.closeEntry();
		}
	}

	/**
	 * @param inputStream
	 * @param metadataMap
	 * @param outputStream
	 * @throws IOException
	 */
	private byte[] overwriteMetadata(InputStream inputStream, Map<String, String> metadataMap) throws IOException {
		try {
			SAXReader reader = new SAXReader();
			Document document = reader.read(inputStream);
			Element rootElement = document.getRootElement();
			Element metadataElement = rootElement.element("metadata");

			@SuppressWarnings("unchecked")
			List<Element> elements = metadataElement.elements();
			for(Element element : elements) {
				String name = element.getName();
				if( ! "meta".equals(name)) {
					String value = metadataMap.get(name);
					if(value != null) {
						element.setText(value);
					}
				}
			}
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			OutputFormat format = OutputFormat.createPrettyPrint();
			XMLWriter writer = new XMLWriter(baos, format);
			writer.write(document);
			return baos.toByteArray();
		} catch(DocumentException e) {
			throw new IOException(e);
		}

	}

	public static void main(String[] args) throws Exception {
		File file = new File("D:\\A graver\\eBooks\\Romans\\N\\Nicci French\\Nicci French - Dans la Peau.epub");
		EBooksFile eBooksFile = open(file);
		eBooksFile.metadataMap.forEach((k, v) -> System.out.println(k + " : " + v));

		Map<String, String> metadataMap = new HashMap<>();
		metadataMap.put("creator", "X creator");
		metadataMap.put("title", "X title");
		metadataMap.put("publisher", "nobody");
		metadataMap.put("contributor", "");
		// eBooksFile.writeMetadatas(metadataMap);

	}
}
