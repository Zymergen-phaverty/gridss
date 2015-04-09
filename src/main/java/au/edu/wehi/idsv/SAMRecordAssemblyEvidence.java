package au.edu.wehi.idsv;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.TextCigarCodec;
import htsjdk.samtools.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import au.edu.wehi.idsv.alignment.AlignerFactory;
import au.edu.wehi.idsv.alignment.Alignment;
import au.edu.wehi.idsv.sam.SAMRecordUtil;
import au.edu.wehi.idsv.sam.SamTags;
import au.edu.wehi.idsv.vcf.VcfFilter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedBytes;

public class SAMRecordAssemblyEvidence implements AssemblyEvidence {
	/*
	 * Using space as a separator as it is reserved in FASTA so shouldn't be in read names
	 */
	public static final String COMPONENT_EVIDENCEID_SEPARATOR = " ";
	private static final Log log = Log.getInstance(SAMRecordAssemblyEvidence.class);
	private final SAMRecord record;
	private final SAMRecord realignment;
	private final AssemblyEvidenceSource source;
	private final BreakendSummary breakend;
	private final boolean isExact;
	private Collection<DirectedEvidence> evidence = new ArrayList<DirectedEvidence>();
	public SAMRecordAssemblyEvidence(AssemblyEvidenceSource source, SAMRecordAssemblyEvidence assembly, SAMRecord realignment) {
		this(source, assembly.getSAMRecord(), realignment);
		this.evidenceIds = assembly.evidenceIds;
		this.evidence = assembly.evidence;
	}
	public SAMRecordAssemblyEvidence(AssemblyEvidenceSource source, SAMRecord assembly, SAMRecord realignment) {
		this.source = source;
		this.record = assembly;
		BreakendSummary bs = calculateBreakend(this.record);
		if (bs instanceof BreakpointSummary && realignment != null && !realignment.getReadUnmappedFlag()
				&& !source.getContext().getRealignmentParameters().realignmentPositionUnique(realignment)) {
			// ignore mapping location of realignment
			bs = ((BreakpointSummary)bs).localBreakend();
		}
		this.breakend = bs;
		this.isExact = calculateIsBreakendExact(this.record.getCigar());
		this.realignment = realignment == null ? getPlaceholderRealignment() : realignment;
		fixReadPair();
	}
	/**
	 * Lazily calculated evidence IDs of the evidence contributing to this breakend
	 * 
	 * Hashes of the IDs are stored due to the overhead of storing the full strings
	 * and the low likelihood and impact of hash collisions
	 */
	private Set<String> evidenceIds = null;
	private void ensureEvidenceIDs() {
		if (evidenceIds == null) {
			Object value = record.getAttribute(SamTags.ASSEMLBY_COMPONENT_EVIDENCEID);
			if (value instanceof String) {
				String[] ids = ((String)value).split(COMPONENT_EVIDENCEID_SEPARATOR);
				evidenceIds = Sets.newHashSet(ids);
			} else {
				evidenceIds = Sets.newHashSet();
			}
		}
	}
	/**
	 * Determines whether the given record is part of the given assembly
	 *
	 * This method is a probabilistic method and it is possible for the record to return true
	 * when the record does not form part of the assembly breakend
	 *
	 * @param evidence
	 * @return true if the record is likely part of the breakend, false if definitely not
	 */
	public boolean isPartOfAssemblyBreakend(DirectedEvidence e) {
		ensureEvidenceIDs();
		return evidenceIds.contains(e.getEvidenceID());
	}
	/**
	 * Hydrates the given evidence back into the assembly evidence set
	 * @param e
	 */
	public void hydrateEvidenceSet(DirectedEvidence e) {
		if (isPartOfAssemblyBreakend(e)) {
			evidence.add(e);
		}
	}
	public Collection<DirectedEvidence> getEvidence() {
		if (evidence == null) {
			return ImmutableList.of();
		}
		ensureEvidenceIDs();
		if (evidenceIds != null) {
			if (evidence.size() < evidenceIds.size()) {
				if (this instanceof RealignedRemoteSAMRecordAssemblyEvidence) {
					// We don't expect rehydration of short SC, OEA, or alternatively mapped evidence at remote position 
				} else {
					log.debug(String.format("Expected %d, found %d support for %s %s%s", evidenceIds.size(), evidence.size(), getEvidenceID(), debugEvidenceMismatch(), debugEvidenceIDsMssingFromEvidence()));
				}
			}
			if (evidence.size() > evidenceIds.size()) {
				// Don't throw exception as the user can't actually do anything about this
				// Just continue with as correct results as we can manage
				log.debug(String.format("Expected %d, found %d support for %s %s%s", evidenceIds.size(), evidence.size(), getEvidenceID(), debugEvidenceMismatch(), debugEvidenceIDsMssingFromEvidence()));
				//log.warn("Hash collision has resulted in evidence being incorrectly attributed to assembly " + getEvidenceID());
			}
		}
		return evidence;
	}
	private String debugEvidenceMismatch() {
		StringBuilder sb = new StringBuilder();
		for (String s : debugEvidenceIDsMssingFromEvidence()) {
			sb.append(" -");
			sb.append(s);
		}
		for (String s : debugEvidenceNotInAssembly()) {
			sb.append(" +");
			sb.append(s);
		}
		return sb.toString();
	}
	private List<String> debugEvidenceIDsMssingFromEvidence() {
		List<String> result = new ArrayList<String>();
		for (String s : evidenceIds) {
			boolean found = false;
			for (DirectedEvidence e : evidence) {
				if (e.getEvidenceID().equals(s)) {
					found = true;
					break;
				}
			}
			if (!found) {
				result.add(s);
			}
		}
		return result;
	}
	private List<String> debugEvidenceNotInAssembly() {
		List<String> result = new ArrayList<String>();
		for (DirectedEvidence e : evidence) {
			boolean found = false;
			for (String s : evidenceIds) {
				if (e.getEvidenceID().equals(s)) {
					found = true;
					break;
				}
			}
			if (!found) {
				result.add(e.getEvidenceID());
			}
		}
		return result;
	}
	private void fixReadPair() {
		if (realignment.getReadUnmappedFlag()) {
			// SAMv1 S2.4
			realignment.setReferenceIndex(record.getReferenceIndex());
			realignment.setAlignmentStart(record.getAlignmentStart());
		}
		SAMRecordUtil.pairReads(this.record, this.realignment);
	}
	private BreakendDirection getBreakendDirection() {
		return getBreakendDirection(record);
	}
	protected static BreakendDirection getBreakendDirection(SAMRecord record) {
		Character c = (Character)record.getAttribute(SamTags.ASSEMBLY_DIRECTION);
		if (c == null) return null;
		return BreakendDirection.fromChar((char)c);
	}
	private SAMRecord getPlaceholderRealignment() {
		SAMRecord placeholder = new SAMRecord(record.getHeader());
		placeholder.setReadUnmappedFlag(true);
		placeholder.setReadBases(getBreakendSequence());
		placeholder.setBaseQualities(getBreakendQuality());
		placeholder.setReadNegativeStrandFlag(false);
		return placeholder;
	}
	private static BreakendSummary calculateBreakend(SAMRecord record) {
		BreakendDirection direction = getBreakendDirection(record);
		int beStart;
		int beEnd;
		if (!calculateIsBreakendExact(record.getCigar())) {
			beStart = record.getAlignmentStart();
			beEnd = record.getAlignmentEnd();
		} else if (direction == BreakendDirection.Forward) {
			beStart = record.getAlignmentEnd();
			beEnd = record.getAlignmentEnd();
		} else {
			beStart = record.getAlignmentStart();
			beEnd = record.getAlignmentStart();
		}
		return new BreakendSummary(record.getReferenceIndex(), direction, beStart, beEnd);
	}
	private static boolean calculateIsBreakendExact(Cigar cigar) {
		for (CigarElement ce : cigar.getCigarElements()) {
			if (ce.getOperator() == CigarOperator.X) return false;
		}
		return true;
	}
	
	private int getAnchorLength() {
		CigarElement ce;
		if (getBreakendDirection() == BreakendDirection.Forward) {
			ce = record.getCigar().getCigarElement(0);
		} else {
			ce = record.getCigar().getCigarElement(record.getCigarLength() - 1);
		}
		if (ce.getOperator() == CigarOperator.X) {
			return 0;
		}
		return record.getReadLength() - getBreakendLength();
	}
	private int getBreakendLength() {
		return getBreakendLength(record);
	}
	private static int getBreakendLength(SAMRecord record) {
		CigarElement ce;
		if (getBreakendDirection(record) == BreakendDirection.Forward) {
			ce = record.getCigar().getCigarElement(record.getCigarLength() - 1);
		} else {
			ce = record.getCigar().getCigarElement(0);
		}
		if (ce.getOperator() == CigarOperator.SOFT_CLIP) return ce.getLength();
		return 0;
	}
	private byte[] getAnchorBytes(byte[] data) {
		int len = getAnchorLength();
		if (getBreakendDirection() == BreakendDirection.Forward) {
			return Arrays.copyOfRange(data, 0, len);
		} else {
			return Arrays.copyOfRange(data, data.length - len, data.length);
		}
	}
	private byte[] getBreakendBytes(byte[] data) {
		int len = getBreakendLength();
		if (getBreakendDirection() == BreakendDirection.Forward) {
			return Arrays.copyOfRange(data, data.length - len, data.length);
		} else {
			return Arrays.copyOfRange(data, 0, len);
		}
	}
	@Override
	public BreakendSummary getBreakendSummary() {
		return breakend;
	}
	@Override
	public byte[] getBreakendSequence() {
		return getBreakendBytes(record.getReadBases());
	}
	@Override
	public byte[] getBreakendQuality() {
		return getBreakendBytes(record.getBaseQualities());
	}
	@Override
	public String getEvidenceID() {
		return record.getReadName();
	}
	@Override
	public AssemblyEvidenceSource getEvidenceSource() {
		return source;
	}
	@Override
	public int getLocalMapq() {
		return record.getMappingQuality();
	}
	@Override
	public int getLocalBaseLength() {
		return getAnchorLength();
	}
	@Override
	public int getLocalMaxBaseQual() {
		if (getAnchorLength() == 0) return 0;
		return UnsignedBytes.toInt(UnsignedBytes.max(getAssemblyAnchorQuals()));
	}
	@Override
	public int getLocalTotalBaseQual() {
		int sum = 0;
		byte[] data = getAssemblyAnchorQuals();
		for (int i = 0; i < data.length; i++) {
			sum += UnsignedBytes.toInt(data[i]);
		}
		return sum;
	}
	@Override
	public byte[] getAssemblySequence() {
		if (isBreakendExact()) return record.getReadBases();
		// Need to remove placeholder Ns from inexact breakend
		return getBreakendDirection() == BreakendDirection.Forward ? Bytes.concat(getAssemblyAnchorSequence(), getBreakendSequence()) : Bytes.concat(getBreakendSequence(), getAssemblyAnchorSequence());
	}
	@Override
	public byte[] getAssemblyAnchorSequence() {
		return getAnchorBytes(record.getReadBases());
	}
	public byte[] getAssemblyAnchorQuals() {
		return getAnchorBytes(record.getBaseQualities());
	}
	@Override
	public int getAssemblyAnchorLength() {
		return getAnchorLength();
	}
	@Override
	public int getAssemblyBaseCount(EvidenceSubset subset) {
		return AttributeConverter.asIntSumTN(record.getAttribute(SamTags.ASSEMBLY_BASE_COUNT), subset);
	}
	@Override
	public int getAssemblySupportCountReadPair(EvidenceSubset subset) {
		return AttributeConverter.asIntSumTN(record.getAttribute(SamTags.ASSEMBLY_READPAIR_COUNT), subset);
	}
	@Override
	public int getAssemblyReadPairLengthMax(EvidenceSubset subset) {
		return AttributeConverter.asIntMaxTN(record.getAttribute(SamTags.ASSEMBLY_READPAIR_LENGTH_MAX), subset);
	}
	@Override
	public int getAssemblySupportCountSoftClip(EvidenceSubset subset) {
		return AttributeConverter.asIntSumTN(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_COUNT), subset);
	}
	@Override
	public int getAssemblySupportCountRemote(EvidenceSubset subset) {
		return AttributeConverter.asIntSumTN(record.getAttribute(SamTags.ASSEMBLY_REMOTE_COUNT), subset);
	}
	public int getAssemblyNonSupportingCount(EvidenceSubset subset) {
		return AttributeConverter.asIntSumTN(record.getAttribute(SamTags.ASSEMBLY_NONSUPPORTING_COUNT), subset);
	}
	@Override
	public int getAssemblySoftClipLengthTotal(EvidenceSubset subset) {
		return AttributeConverter.asIntSumTN(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_TOTAL), subset);
	}
	@Override
	public int getAssemblySoftClipLengthMax(EvidenceSubset subset) {
		return AttributeConverter.asIntMaxTN(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_MAX), subset);
	}
	public float getAssemblySupportReadPairQualityScore(EvidenceSubset subset) {
		return (float)AttributeConverter.asDoubleSumTN(record.getAttribute(SamTags.ASSEMBLY_READPAIR_QUAL), subset);
	}
	public float getAssemblySupportSoftClipQualityScore(EvidenceSubset subset) {
		return (float)AttributeConverter.asDoubleSumTN(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_QUAL), subset);
	}
	public float getAssemblySupportRemoteQualityScore(EvidenceSubset subset) {
		return (float)AttributeConverter.asDoubleSumTN(record.getAttribute(SamTags.ASSEMBLY_REMOTE_QUAL), subset);
	}
	public float getAssemblyNonSupportingQualityScore(EvidenceSubset subset) {
		return (float)AttributeConverter.asDoubleSumTN(record.getAttribute(SamTags.ASSEMBLY_NONSUPPORTING_QUAL), subset);
	}
	@Override
	public boolean isAssemblyFiltered() {
		return StringUtils.isNotBlank((String)record.getAttribute(SamTags.ASSEMLBY_FILTERS));
	}
	@Override
	public void filterAssembly(VcfFilter reason) {
		String tag = (String)record.getAttribute(SamTags.ASSEMLBY_FILTERS);
		if (StringUtils.isBlank(tag)) {
			tag = reason.filter();
		} else if (!tag.contains(reason.filter())) {
			tag = tag + "," + reason.filter();
		}
		record.setAttribute(SamTags.ASSEMLBY_FILTERS, tag);
	}
	@Override
	public List<VcfFilter> getFilters() {
		List<VcfFilter> list = Lists.newArrayList();
		String filters = (String)record.getAttribute(SamTags.ASSEMLBY_FILTERS);
		if (!StringUtils.isEmpty(filters)) {
			for (String s : filters.split(",")) {
				list.add(VcfFilter.get(s));
			}
		}
		return list;
	}
	public SAMRecord getSAMRecord() {
		return record;
	}
	public SAMRecord getRemoteSAMRecord() {
		return realignment;
	}
	@Override
	public String toString() {
		return String.format("A  %s N=%s", getBreakendSummary(), getEvidenceID());
	}
	@Override
	public boolean isBreakendExact() {
		return isExact;
	}
	@Override
	public float getBreakendQual() {
		if (getBreakendLength() == 0) return 0;
		int evidenceCount = getAssemblySupportCountReadPair(EvidenceSubset.ALL)
				+ getAssemblySupportCountSoftClip(EvidenceSubset.ALL);
		double qual = getAssemblySupportReadPairQualityScore(EvidenceSubset.ALL)
				+ getAssemblySupportSoftClipQualityScore(EvidenceSubset.ALL);
		if (source.getContext().getAssemblyParameters().excludeNonSupportingEvidence) {
			evidenceCount -= getAssemblyNonSupportingCount(EvidenceSubset.ALL);
			qual -= getAssemblyNonSupportingQualityScore(EvidenceSubset.ALL);
		}
		// currently redundant as evidence is capped by local mapq so cap is always larger than the actual score.
		qual = Math.min(getLocalMapq() * evidenceCount, qual);
		return (float)qual;
	}
	/**
	 * Performs local Smith-Watermann alignment of assembled contig
	 * to remove artifacts caused by misalignment of soft clipped reads 
	 * @return Assembly contig with aligned anchor.
	 */
	public SAMRecordAssemblyEvidence realign() {
		if (!this.isExact) throw new RuntimeException("Sanity check failure: realignment of unanchored assemblies not yet implemented.");
		if (getBreakendSummary() instanceof DirectedBreakpoint) throw new IllegalStateException("Unable to realign breakpoint assemblies");
		AssemblyParameters ap = source.getContext().getAssemblyParameters();
		int refIndex = getBreakendSummary().referenceIndex;
		SAMSequenceRecord refSeq = source.getContext().getDictionary().getSequence(refIndex);
		int start = Math.max(1, record.getAlignmentStart() - ap.realignmentWindowSize - (getBreakendSummary().direction == BreakendDirection.Backward ? getBreakendLength() : 0));
		int end = Math.min(refSeq.getSequenceLength(), record.getAlignmentEnd() + ap.realignmentWindowSize + (getBreakendSummary().direction == BreakendDirection.Forward ? getBreakendLength() : 0));
		byte[] ass = record.getReadBases();
		byte[] ref = source.getContext().getReference().getSubsequenceAt(refSeq.getSequenceName(), start, end).getBases();
		
        Alignment alignment = AlignerFactory.create().align_smith_waterman(ass, ref);        
        Cigar cigar = TextCigarCodec.getSingleton().decode(alignment.getCigar());
        SAMRecord newAssembly = SAMRecordUtil.clone(record);
		newAssembly.setReadName(newAssembly.getReadName() + "_r");
        newAssembly.setAlignmentStart(start + alignment.getStartPosition());
        if (!cigar.equals(record.getCigar())) {
        	newAssembly.setCigar(cigar);
        	newAssembly.setAttribute(SamTags.ORIGINAL_CIGAR, record.getCigarString());
        }
        SAMRecordAssemblyEvidence realigned = AssemblyFactory.hydrate(getEvidenceSource(), newAssembly);
        return realigned;
	}
	/**
	 * Determines whether the assembly is of the reference allele
	 * @return
	 */
	public boolean isReferenceAssembly() {
		return isExact && getBreakendLength() == 0;
	}
}
