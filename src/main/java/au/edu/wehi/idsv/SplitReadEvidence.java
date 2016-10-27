package au.edu.wehi.idsv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.NotImplementedException;

import au.edu.wehi.idsv.sam.ChimericAlignment;
import au.edu.wehi.idsv.sam.SAMRecordUtil;
import gridss.ComputeSamTags;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;

/**
 * Chimeric alignment based support for a structural variant
 * @author Daniel Cameron
 *
 */
public class SplitReadEvidence extends SingleReadEvidence implements DirectedBreakpoint {
	private ChimericAlignment remoteAlignment;
	private SplitReadEvidence(SAMEvidenceSource source, SAMRecord record, BreakendSummary location,
			int offsetLocalStart, int offsetLocalEnd,
			int offsetUnmappedStart, int offsetUnmappedEnd,
			int offsetRemoteStart, int offsetRemoteEnd, ChimericAlignment remoteAlignment) {
		super(source, record, location, offsetLocalStart, offsetLocalEnd, offsetUnmappedStart, offsetUnmappedEnd, offsetRemoteStart, offsetRemoteEnd);
		this.remoteAlignment = remoteAlignment;
	}
	public static List<SplitReadEvidence> create(SAMEvidenceSource source, SAMRecord record) {
		if (record.getReadUnmappedFlag() || record.getCigar() == null) return Collections.emptyList();
		List<ChimericAlignment> aln = ChimericAlignment.getChimericAlignments(record);
		if (aln.size() <= 1) return Collections.emptyList();
		if (record.getCigar().getFirstCigarElement().getOperator() == CigarOperator.HARD_CLIP
				|| record.getCigar().getLastCigarElement().getOperator() == CigarOperator.HARD_CLIP) {
			throw new IllegalArgumentException("Hard clipped split reads are not supported. Please run " + ComputeSamTags.class.getName() + " to soften hard clips.");
		}
		List<SplitReadEvidence> list = new ArrayList<>(2);
		ChimericAlignment chim = new ChimericAlignment(record);
		int offset = SAMRecordUtil.getFirstAlignedBaseReadOffset(record);
		ChimericAlignment pre = aln.stream().filter(ca -> ca.getFirstAlignedBaseReadOffset() < offset).max(ChimericAlignment.ByReadOffset).orElse(null);
		ChimericAlignment post = aln.stream().filter(ca -> ca.getFirstAlignedBaseReadOffset() > offset).min(ChimericAlignment.ByReadOffset).orElse(null);
		SAMSequenceDictionary dict = source != null ? source.getContext().getDictionary() : record.getHeader().getSequenceDictionary();
		// Read is AAAXBBBYCCC
		// we are alignment B
		// pre is A
		// post is C
		// X,Y are unaligned bases
		final int rl1 = record.getReadLength() - 1;
		int startOffset = chim.getFirstAlignedBaseReadOffset();
		int endOffset = chim.getLastAlignedBaseReadOffset();
		if (pre != null) {
			BreakpointSummary bs = new BreakpointSummary(chim.predecessorBreakend(dict), pre.successorBreakend(dict));
			int preStartOffset = 0; // ignore the actual alignment and just go out to the end of the read so we can assemble across multiple breakpoints
			int preEndOffset = pre.getLastAlignedBaseReadOffset();
			if (record.getReadNegativeStrandFlag()) {
				list.add(new SplitReadEvidence(source, record, bs,
						rl1 - startOffset, rl1 - (INCLUDE_CLIPPED_ANCHORING_BASES ? record.getReadLength() : endOffset),
						rl1 - preEndOffset, rl1 - startOffset,
						rl1 - preStartOffset, rl1 - preEndOffset,
						pre));
			} else {
				list.add(new SplitReadEvidence(source, record, bs,
					startOffset, INCLUDE_CLIPPED_ANCHORING_BASES ? record.getReadLength() : endOffset,
					preEndOffset, startOffset,
					preStartOffset, preEndOffset,
					pre));
			}
		}
		if (post != null) {
			BreakpointSummary bs = new BreakpointSummary(chim.successorBreakend(dict), post.predecessorBreakend(dict));
			int postStartOffset = post.getFirstAlignedBaseReadOffset();
			int postEndOffset = rl1;
			if (record.getReadNegativeStrandFlag()) {
				list.add(new SplitReadEvidence(source, record, bs,
						rl1 - (INCLUDE_CLIPPED_ANCHORING_BASES ? 0 : startOffset), rl1 - endOffset,
						rl1 - endOffset, rl1 - postStartOffset,
						rl1 - postStartOffset, rl1 - postEndOffset,
						post));
			} else {
				list.add(new SplitReadEvidence(source, record, bs,
					INCLUDE_CLIPPED_ANCHORING_BASES ? 0 : startOffset, endOffset,
					endOffset, postStartOffset,
					postStartOffset, postEndOffset,
					post));
			}
		}
		return list;
	}
	
	@Override
	public BreakpointSummary getBreakendSummary() {
		return (BreakpointSummary)super.getBreakendSummary();
	}
	@Override
	protected void buildEvidenceID(StringBuilder sb) {
		super.buildEvidenceID(sb);
		sb.append("_");
		sb.append(getBreakendSummary().direction.toChar());
	}

	@Override
	public int getRemoteMapq() {
		return remoteAlignment.mapq;
	}
	@Override
	public float getBreakendQual() {
		return getBreakpointQual();
	}
	@Override
	public float getBreakpointQual() {
		throw new NotImplementedException("Need new model for split reads that do not originate from soft clips that scores both sides of the split equally.");
		//return (float)getEvidenceSource().getContext().getConfig().getScoring().getModel().scoreSplitRead(getEvidenceSource().getMetrics(),
		//		getBreakendSummary().direction == BreakendDirection.Forward ? SAMRecordUtil.getEndClipLength(getSAMRecord()) : SAMRecordUtil.getStartClipLength(getSAMRecord()),
		//		getLocalMapq(), getRemoteMapq());
	}
	@Override
	public DirectedBreakpoint asRemote() {
		throw new NotImplementedException();
	}
}