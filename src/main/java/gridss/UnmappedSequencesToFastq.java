package gridss;

import au.edu.wehi.idsv.ProgressLoggingSAMRecordIterator;
import au.edu.wehi.idsv.sam.ChimericAlignment;
import au.edu.wehi.idsv.sam.SAMRecordUtil;
import au.edu.wehi.idsv.util.AsyncBufferedIterator;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import htsjdk.samtools.*;
import htsjdk.samtools.fastq.FastqRecord;
import htsjdk.samtools.fastq.FastqWriter;
import htsjdk.samtools.fastq.FastqWriterFactory;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.util.SequenceUtil;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.StandardOptionDefinitions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@CommandLineProgramProperties(
		summary = "Exports unmapped sequences to fastq. " +
				"Only primary read alignments are exported.",
        oneLineSummary = "Exports unmapped sequences to fastq.",
        programGroup = gridss.cmdline.programgroups.DataConversion.class
)
public class UnmappedSequencesToFastq extends CommandLineProgram {
	private static final Log log = Log.getInstance(UnmappedSequencesToFastq.class);
	@Argument(shortName= StandardOptionDefinitions.INPUT_SHORT_NAME, doc="Input SAM/BAM files")
	public List<File> INPUT;
	@Argument(shortName= StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc="Output fastq file")
	public File OUTPUT;
	@Argument(doc="Minimum length of sequence export. " +
			"Generally speaking, very short soft clips are uninformative.", optional=true)
	public int MIN_SEQUENCE_LENGTH = 20;
	@Argument(doc="Include soft clipped bases. " +
			"For split read alignments, the largest contiguous sequence not aligned to the reference is used.", optional=true)
	public boolean INCLUDE_SOFT_CLIPPED_BASES = true;
	@Argument(doc="Include unmapped bases that are flanked by chimeric alignments.", optional=true)
	public boolean INCLUDE_UNMAPPED_INTERNAL_BASES = false;
	@Argument(doc="Ensure exported names are unique by suffixing with '/1' or '/2'", optional=true)
	public boolean UNIQUE_NAME = false;
	@Argument(doc="Output file containing read names of fragments with an alignment to the reference." +
			" May contained duplicate read names if a soft clipped reads has an unmapped mate.", optional=true)
	public File PARTIALLY_ALIGNED_READ_NAMES = null;

	public static void main(String[] argv) {
		System.exit(new UnmappedSequencesToFastq().instanceMain(argv));
	}

	@Override
	protected int doWork() {
		for (File input : INPUT) {
			IOUtil.assertFileIsReadable(input);
		}
		IOUtil.assertFileIsWritable(OUTPUT);
		Writer readNameWriter = null;
		try (FastqWriter fqw = new FastqWriterFactory().newWriter(OUTPUT)) {
			if (PARTIALLY_ALIGNED_READ_NAMES != null) {
				readNameWriter = new BufferedWriter(new FileWriter(PARTIALLY_ALIGNED_READ_NAMES));
			}
			for (File input : INPUT) {
				try (SamReader reader = SamReaderFactory.makeDefault().open(input)) {
					try (SAMRecordIterator rawIt = reader.iterator()) {
						ProgressLoggingSAMRecordIterator loggedIt = new ProgressLoggingSAMRecordIterator(rawIt, new ProgressLogger(log, 10000000));
						try (AsyncBufferedIterator<SAMRecord> it = new AsyncBufferedIterator(loggedIt, "samIterator")) {
							while (it.hasNext()) {
								SAMRecord r = it.next();
								FastqRecord fq = getUnmappedFastqRecord(r);
								if (fq != null && fq.getReadLength() >= MIN_SEQUENCE_LENGTH) {
									fqw.write(fq);
									if (readNameWriter != null) {
										if (!r.getReadUnmappedFlag() || (r.getReadPairedFlag() && !r.getMateUnmappedFlag())) {
											readNameWriter.write(r.getReadName());
											readNameWriter.write('\n');
										}
									}
								}
							}
						}
					}
				}
			}
			if (readNameWriter != null) {
				readNameWriter.close();
			}
		} catch (IOException e) {
			log.error(e);
			return -1;
		}
		return 0;
	}
	private FastqRecord getUnmappedFastqRecord(SAMRecord record) {
		FastqRecord fq = null;
		if (!record.isSecondaryOrSupplementary()) {
			byte[] bases = null;
			byte[] quals = null;
			if (record.getReadUnmappedFlag()) {
				bases = record.getReadBases();
				quals = record.getBaseQualities();
			} else if (INCLUDE_SOFT_CLIPPED_BASES && (SAMRecordUtil.getStartClipLength(record) >= MIN_SEQUENCE_LENGTH || SAMRecordUtil.getEndClipLength(record) >= MIN_SEQUENCE_LENGTH)) {
				// grab the largest contiguous subread not aligned
				List<ChimericAlignment> ca = Lists.newArrayList(new ChimericAlignment(record));
				ca.addAll(ChimericAlignment.getChimericAlignments(record));
				Range<Integer> mostUnaligned = Range.closed(0, 0);
				for (Range<Integer> r : ChimericAlignment.getUnalignedIntervals(ca)
						.asRanges()
						.stream()
						// Only get start/end ranges if INCLUDE_UNMAPPED_INTERNAL_BASES is false
						.filter(ur -> INCLUDE_UNMAPPED_INTERNAL_BASES || ur.lowerEndpoint() == 0 || ur.upperEndpoint() == record.getReadLength())
						.collect(Collectors.toList())) {
					if (r.upperEndpoint() - r.lowerEndpoint() > mostUnaligned.upperEndpoint() - mostUnaligned.lowerEndpoint()) {
						mostUnaligned = r;
					}
				}
				if (mostUnaligned.upperEndpoint() != mostUnaligned.lowerEndpoint()) {
					SAMRecordUtil.hardClipToN(record);
					bases = record.getReadBases().clone();
					quals = record.getBaseQualities().clone();
					if (record.getReadNegativeStrandFlag()) {
						SequenceUtil.reverseComplement(bases);
						SequenceUtil.reverseQualities(quals);
					}
					bases = Arrays.copyOfRange(bases, mostUnaligned.lowerEndpoint(), mostUnaligned.upperEndpoint());
					quals = Arrays.copyOfRange(quals, mostUnaligned.lowerEndpoint(), mostUnaligned.upperEndpoint());
				}
			}
			if (bases != null) {
				String uniqueName = record.getReadName();
				if (UNIQUE_NAME) {
					if (record.getFirstOfPairFlag()) {
						uniqueName += "/1";
					} else if (record.getSecondOfPairFlag()) {
						uniqueName += "/2";
					}
				}
				fq = new FastqRecord(
						uniqueName,
						new String(bases, StandardCharsets.UTF_8),
						null,
						SAMUtils.phredToFastq(quals));
			}
		}
		return fq;
	}
}
