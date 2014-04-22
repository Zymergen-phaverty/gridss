package au.edu.wehi.socrates;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.OptionBuilder;
import org.broadinstitute.variant.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.variant.variantcontext.writer.VariantContextWriterFactory;
import org.broadinstitute.variant.vcf.VCFRecordCodec;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import au.edu.wehi.socrates.util.SAMRecordMateCoordinateComparator;
import au.edu.wehi.socrates.util.SAMRecordSummary;
import net.sf.picard.analysis.CollectInsertSizeMetrics;
import net.sf.picard.analysis.InsertSizeMetrics;
import net.sf.picard.analysis.MetricAccumulationLevel;
import net.sf.picard.analysis.directed.InsertSizeMetricsCollector;
import net.sf.picard.cmdline.CommandLineProgram;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.fastq.FastqRecord;
import net.sf.picard.fastq.FastqWriter;
import net.sf.picard.fastq.FastqWriterFactory;
import net.sf.picard.filter.AggregateFilter;
import net.sf.picard.filter.DuplicateReadFilter;
import net.sf.picard.filter.FailsVendorReadQualityFilter;
import net.sf.picard.filter.FilteringIterator;
import net.sf.picard.filter.SamRecordFilter;
import net.sf.picard.io.IoUtil;
import net.sf.picard.metrics.MetricsFile;
import net.sf.picard.util.Log;
import net.sf.picard.util.ProgressLogger;
import net.sf.samtools.BAMRecordCodec;
import net.sf.samtools.SAMException;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileWriter;
import net.sf.samtools.SAMFileWriterFactory;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;
import net.sf.samtools.SAMSequenceDictionary;
import net.sf.samtools.SAMFileHeader.SortOrder;
import net.sf.samtools.util.CloseableIterator;
import net.sf.samtools.util.CollectionUtil;
import net.sf.samtools.util.SortingCollection;

public class GenerateDirectedBreakpoints extends CommandLineProgram {

    private static final String PROGRAM_VERSION = "0.1";

    // The following attributes define the command-line arguments
    @Usage
    public String USAGE = getStandardUsagePreamble() + "Generated directed breakpoints." + PROGRAM_VERSION;

    @Option(doc = "Coordinate sorted input file containing reads supporting putative structural variations",
            optional = false,
            shortName = StandardOptionDefinitions.INPUT_SHORT_NAME)
    public File INPUT;
    @Option(doc = "DP and OEA read pairs sorted by coordinate of mapped mate read.",
            optional = false,
            shortName = "MCI")
    public File MATE_COORDINATE_INPUT = null;    
    @Option(doc = "Directed single-ended breakpoints. A placeholder contig is output as the breakpoint partner.",
            optional = false,
            shortName=StandardOptionDefinitions.OUTPUT_SHORT_NAME)
    public File VCF_OUTPUT;
    @Option(doc = "FASTQ of reference strand sequences of putative breakpoints excluding anchored bases. These sequences are used to align breakpoints",
            optional = false,
            shortName = "FQ")
    public File FASTQ_OUTPUT = null;
    @Option(doc = "Picard metrics file generated by ExtractEvidence",
            optional = true)
    public File METRICS = null;
    @Option(doc = "Minimum alignment mapq",
    		optional=true)
    public int MIN_MAPQ = 5;
    @Option(doc = "Length threshold of long soft-clip",
    		optional=true)
    public int LONG_SC_LEN = 25;
    @Option(doc = "Minimum alignment percent identity to reference. Takes values in the range 0-100.",
    		optional=true)
    public float MIN_PERCENT_IDENTITY = 95;
    @Option(doc = "Minimum average base quality score of soft clipped sequence",
    		optional=true)
    public float MIN_LONG_SC_BASE_QUALITY = 5;
    @Option(doc = "k-mer used for de bruijn graph construction",
    		optional=true,
    		shortName="K")
    public int KMER = 25;
    private Log log = Log.getInstance(GenerateDirectedBreakpoints.class);
    @Override
	protected int doWork() {
    	SAMFileReader.setDefaultValidationStringency(SAMFileReader.ValidationStringency.SILENT);
    	try {
    		if (METRICS == null) {
    			METRICS = FileNamingConvention.GetMetrics(INPUT);
    		}
    		IoUtil.assertFileIsReadable(METRICS);
    		IoUtil.assertFileIsReadable(INPUT);
    		IoUtil.assertFileIsReadable(MATE_COORDINATE_INPUT);
    		
    		//final ProgressLogger progress = new ProgressLogger(log);
	    	final SAMFileReader reader = new SAMFileReader(INPUT);
	    	final SAMFileReader mateReader = new SAMFileReader(MATE_COORDINATE_INPUT);
	    	final SAMFileHeader header = reader.getFileHeader();
	    	final SAMSequenceDictionary dictionary = header.getSequenceDictionary();
	    	final RelevantMetrics metrics = new RelevantMetrics(METRICS);
	    	
			final PeekingIterator<SAMRecord> iter = Iterators.peekingIterator(reader.iterator());
			final PeekingIterator<SAMRecord> mateIter = Iterators.peekingIterator(mateReader.iterator());
			final FastqWriter fastqWriter = new FastqWriterFactory().newWriter(FASTQ_OUTPUT);
			final VariantContextWriter vcfWriter = VariantContextWriterFactory.create(VCF_OUTPUT, dictionary);
			
			DirectedEvidenceIterator dei = new DirectedEvidenceIterator(iter, mateIter, null, null, dictionary, metrics.getMaxFragmentSize());
			ReadEvidenceAssembler assembler = new DeBruijnAssembler(KMER);
			while (dei.hasNext()) {
				DirectedEvidence readEvidence = dei.next();
				if (readEvidence.getClass() == SoftClipEvidence.class) {
					SoftClipEvidence sce = (SoftClipEvidence)readEvidence;
					if (sce.getMappingQuality() > MIN_MAPQ &&
							sce.getSoftClipLength() > LONG_SC_LEN &&
							sce.getAlignedPercentIdentity() > MIN_PERCENT_IDENTITY &&
							sce.getAverageClipQuality() > MIN_LONG_SC_BASE_QUALITY) {
						FastqRecord fastq = BreakpointFastqEncoding.getRealignmentFastq(sce);
						fastqWriter.write(fastq);
					}
				}
				processAssemblyEvidence(assembler.addEvidence(readEvidence), fastqWriter, vcfWriter);
			}
			processAssemblyEvidence(assembler.endOfEvidence(), fastqWriter, vcfWriter);
	    	fastqWriter.close();
	    	vcfWriter.close();
	    	reader.close();
	    	mateReader.close();
    	} catch (IOException e) {
    		log.error(e);
    		throw new RuntimeException(e);
    	}
        return 0;
    }
    private void processAssemblyEvidence(Iterable<AssemblyEvidence> evidenceList, FastqWriter fastqWriter, VariantContextWriter vcfWriter) {
    	for (AssemblyEvidence a : evidenceList) {
    		FastqRecord fastq = BreakpointFastqEncoding.getRealignmentFastq(a);
    		fastqWriter.write(fastq);
    		vcfWriter.add(a);
    	}
    }
	public static void main(String[] argv) {
        System.exit(new GenerateDirectedBreakpoints().instanceMain(argv));
    }
}
