package org.fagu.ebooks.bootstrap;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.fagu.ebooks.EBooksFile;


/**
 * @author fagu
 */
public class UpdateAuthorBootstrap {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if(args.length != 1) {
			System.err.println("Usage: java " + UpdateAuthorBootstrap.class.getName() + " <folder>");
			return;
		}

		File folder = new File(args[0]);
		File[] files = folder.listFiles(f -> f.isFile() && "epub".equalsIgnoreCase(FilenameUtils.getExtension(f.getName())));
		if(files == null) {
			return;
		}

		Map<String, String> metadataMap = new HashMap<>();
		metadataMap.put("creator", folder.getName());
		metadataMap.put("publisher", "nobody");
		metadataMap.put("contributor", "");

		System.out.println("Update author with " + folder.getName());
		System.out.println();

		for(File file : files) {
			System.out.print(file.getName() + "...");
			try {
				EBooksFile eBooksFile = EBooksFile.open(file);
				if(eBooksFile.needToWriteMetadatas(metadataMap)) {
					System.out.println("   [updating]");
					File newFile = eBooksFile.writeMetadatas(metadataMap);
					file.delete();
					newFile.renameTo(file);
				} else {
					System.out.println();
				}
			} catch(Exception e) {
				System.out.println();
				e.printStackTrace();
			}
		}
	}

}
