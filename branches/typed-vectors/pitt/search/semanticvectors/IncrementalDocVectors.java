/**
   Copyright (c) 2008, Arizona State University.

   All rights reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are
   met:

 * Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
   copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided
   with the distribution.

 * Neither the name of the University of Pittsburgh nor the names
   of its contributors may be used to endorse or promote products
   derived from this software without specific prior written
   permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
   CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 **/

package pitt.search.semanticvectors;

import java.io.File;
import java.io.IOException;
import java.lang.Integer;
import java.util.Arrays;
import java.util.logging.Logger;

import org.apache.lucene.index.*;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.FSDirectory;

import pitt.search.semanticvectors.vectors.Vector;
import pitt.search.semanticvectors.vectors.VectorFactory;
import pitt.search.semanticvectors.vectors.VectorUtils;

/**
 * Generates document vectors incrementally.
 * 
 * @author Trevor Cohen, Dominic Widdows
 */
public class IncrementalDocVectors {
  private static final Logger logger = Logger.getLogger(
      IncrementalDocVectors.class.getCanonicalName());

  private VectorStore termVectorData;
  private IndexReader indexReader;
  private String[] fieldsToIndex;
  private LuceneUtils lUtils;
  private String vectorFileName;
  private int dimension;
  
  private IncrementalDocVectors() {};
  
  /**
   * Creates incremental doc vectors, getting everything it needs from a
   * TermVectorsFromLucene object and a Lucene Index directory, and writing to a named file.
   * 
   * @param termVectorData Has all the information needed to create doc vectors.
   * @param indexDir Directory of the Lucene Index used to generate termVectorData
   * @param fieldsToIndex String[] containing fields indexed when generating termVectorData
   * @param vectorFileName Filename for the document vectors
   */
  public static void createIncrementalDocVectors(
		  VectorStore termVectorData, String indexDir,
		  String[] fieldsToIndex, String vectorFileName, int dimension) throws IOException {
	IncrementalDocVectors incrementalDocVectors = new IncrementalDocVectors();
	incrementalDocVectors.termVectorData = termVectorData;
	incrementalDocVectors.indexReader = IndexReader.open(FSDirectory.open(new File(indexDir)));
	incrementalDocVectors.fieldsToIndex = fieldsToIndex;
	incrementalDocVectors.vectorFileName = vectorFileName;
	incrementalDocVectors.dimension = dimension;
    if (incrementalDocVectors.lUtils == null) {
    	incrementalDocVectors.lUtils = new LuceneUtils(indexDir);
    }
    incrementalDocVectors.trainIncrementalDocVectors();
  }
    
  private void trainIncrementalDocVectors() throws IOException {
    int numdocs = indexReader.numDocs();

    // Open file and write headers.
    File vectorFile = new File(vectorFileName);
    String parentPath = vectorFile.getParent();
    if (parentPath == null) parentPath = "";
    FSDirectory fsDirectory = FSDirectory.open(new File(parentPath));
    IndexOutput outputStream = fsDirectory.createOutput(vectorFile.getName());

    logger.info("Write vectors incrementally to file " + vectorFile);

    // Write header giving number of dimensions for all vectors.
    outputStream.writeString("-dimensions");
    outputStream.writeInt(dimension);

    // Iterate through documents.
    for (int dc = 0; dc < numdocs; dc++) {
      // Output progress counter.
      if ((dc > 0) && ((dc % 50000 == 0) || ( dc < 50000 && dc % 10000 == 0 ))) {
        logger.fine("Processed " + dc + " documents ... ");
      }
    
      String docID = Integer.toString(dc); 
      // Use filename and path rather than Lucene index number for document vector.
      if (this.indexReader.document(dc).getField(Flags.docidfield) != null) {
        docID = this.indexReader.document(dc).getField(Flags.docidfield).stringValue();
        if (docID.length() == 0) {
          logger.warning("Empty document name!!! This will cause problems ...");
          logger.warning("Please set -docidfield to a nonempty field in your Lucene index.");
        }
      }

      Vector docVector = VectorFactory.createZeroVector(Flags.vectortype, dimension);

      for (String fieldName: fieldsToIndex) {
        TermFreqVector vex =
          indexReader.getTermFreqVector(dc, fieldName);

        if (vex != null) {
          // Get terms in document and term frequencies.
          String[] terms = vex.getTerms();
          int[] freqs = vex.getTermFrequencies();

          for (int b = 0; b < freqs.length; ++b) {
            String term_string = terms[b];
            int freq = freqs[b];
            float localweight = freq;
            float globalweight = 1;

            if (Flags.termweight.equals("logentropy")) {
              //local weighting: 1+ log (local frequency)
              localweight = new Double(1 + Math.log(localweight)).floatValue();
              Term term = new Term(fieldName,term_string);
              globalweight = globalweight * lUtils.getEntropy(term);
            }

            // Add contribution from this term, excluding terms that
            // are not represented in termVectorData.
            try {
              Vector termVector = termVectorData.getVector(term_string);
              if (termVector != null && termVector.getDimensions() > 0) {
                docVector.superpose(termVector, localweight * globalweight, null);
              }
            } catch (NullPointerException npe) {
              // Don't normally print anything - too much data!
              // TODO(dwiddows): Replace with a configurable logging system.
              // logger.info("term "+term+ " not represented");
            }
          }
        }
        // All fields in document have been processed.
        // Write out documentID and normalized vector.
        outputStream.writeString(docID);
        docVector.normalize();
        docVector.writeToLuceneStream(outputStream);
      }
    } // Finish iterating through documents.

    logger.info("Finished writing vectors.");
    outputStream.flush();
    outputStream.close();
    fsDirectory.close();
  }

  public static void main(String[] args) throws Exception {
   

        try {
          args = Flags.parseCommandLineFlags(args);
        } catch (IllegalArgumentException e) {
         
          throw e;
        }

        // Only two arguments should remain, the path to the Lucene index.
        if (args.length != 2) {
        
          throw (new IllegalArgumentException("After parsing command line flags, there were " + args.length
                                              + " arguments, instead of the expected 2."));
        }

        String vectorFile = args[0].replaceAll("\\.bin","")+"_docvectors.bin";
        VectorStoreRAM vsr = new VectorStoreRAM();
        vsr.initFromFile(args[0]);
        
        logger.info("Minimum frequency = " + Flags.minfrequency);
        logger.info("Maximum frequency = " + Flags.maxfrequency);
        logger.info("Number non-alphabet characters = " + Flags.maxnonalphabetchars);
        logger.info("Contents fields are: " + Arrays.toString(Flags.contentsfields));

    
    createIncrementalDocVectors(vsr, args[1], Flags.contentsfields, vectorFile, Flags.dimensions);
  }
}