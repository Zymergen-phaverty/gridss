package au.edu.wehi.idsv.debruijn;

import htsjdk.samtools.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import au.edu.wehi.idsv.AssemblyEvidence;
import au.edu.wehi.idsv.AssemblyEvidenceSource;
import au.edu.wehi.idsv.AssemblyFactory;
import au.edu.wehi.idsv.BreakendDirection;
import au.edu.wehi.idsv.DirectedEvidence;
import au.edu.wehi.idsv.LinearGenomicCoordinate;
import au.edu.wehi.idsv.NonReferenceReadPair;
import au.edu.wehi.idsv.ProcessingContext;
import au.edu.wehi.idsv.RemoteEvidence;
import au.edu.wehi.idsv.SAMEvidenceSource;
import au.edu.wehi.idsv.SoftClipEvidence;

import com.google.common.collect.Sets;

public abstract class DeBruijnVariantGraph<T extends DeBruijnNodeBase> extends DeBruijnGraphBase<T> {
	/**
	 * Extremely large support sets are computationally expensive
	 */
	private static final int SUPPORT_SIZE_HARD_LIMIT = 100000;
	private static final Log log = Log.getInstance(DeBruijnVariantGraph.class);
	protected final ProcessingContext processContext;
	protected final AssemblyEvidenceSource source;
	public DeBruijnVariantGraph(ProcessingContext context, AssemblyEvidenceSource source, int k) {
		super(k);
		this.processContext = context;
		this.source = source;
	}
	public void addEvidence(DirectedEvidence evidence) {
		if (evidence instanceof NonReferenceReadPair) {
			addEvidence((NonReferenceReadPair)evidence);
		} else if (evidence instanceof SoftClipEvidence) {
			addEvidence((SoftClipEvidence)evidence);
		} else {
			throw new RuntimeException(String.format("NYI: Unable to add %s evidence to de bruijn graph", evidence));
		}
	}
	protected abstract T createNode(VariantEvidence evidence, int readKmerOffset, ReadKmer kmer);
	@Override
	protected T merge(T node, T toAdd) {
		node.add(toAdd);
		return node;
	}
	@Override
	protected T remove(T node, T toRemove) {
		node.remove(toRemove);
		return node;
	}
	public void addEvidence(NonReferenceReadPair pair) {
		VariantEvidence graphEvidence = new VariantEvidence(getK(), pair, processContext.getLinear());
		addEvidenceKmers(graphEvidence);
	}
	public void addEvidence(SoftClipEvidence read) {
		if (read instanceof RemoteEvidence && !processContext.getAssemblyParameters().includeRemoteSoftClips) {
			// ignore remote soft clips
			return;
		}
		VariantEvidence graphEvidence = new VariantEvidence(getK(), read, processContext.getLinear());
		addEvidenceKmers(graphEvidence);
	}
	public void removeEvidence(NonReferenceReadPair pair) {
		VariantEvidence graphEvidence = new VariantEvidence(getK(), pair, processContext.getLinear());
		removeEvidenceKmers(graphEvidence);
	}
	public void removeEvidence(SoftClipEvidence read) {
		VariantEvidence graphEvidence = new VariantEvidence(getK(), read, processContext.getLinear());
		removeEvidenceKmers(graphEvidence);
	}
	protected void addEvidenceKmers(VariantEvidence evidence) {
		int readKmerOffset = 0;
		for (ReadKmer readKmer : evidence.getReadKmers()) {
			if (!shouldSkipKmer(evidence, readKmerOffset, readKmer)) {
				T node = createNode(evidence, readKmerOffset, readKmer);
				T graphNode = add(readKmer.kmer, node);
				onEvidenceAdded(graphNode, node, evidence, readKmerOffset, readKmer);
			}
			readKmerOffset++;
		}
	}
	protected boolean shouldSkipKmer(VariantEvidence evidence, int readKmerOffset, ReadKmer readKmer) {
		return evidence.isSkippedKmer(readKmerOffset) || readKmer.containsAmbiguousBases;
	}
	protected void removeEvidenceKmers(VariantEvidence evidence) {
		int readKmerOffset = 0;
		for (ReadKmer readKmer : evidence.getReadKmers()) {
			if (!shouldSkipKmer(evidence, readKmerOffset, readKmer)) {
				T node = createNode(evidence, readKmerOffset, readKmer);
				T graphNode = remove(readKmer.kmer, node);
				onEvidenceRemoved(graphNode, node, evidence, readKmerOffset, readKmer);
			}
			readKmerOffset++;
		}
	}
	/**
	 * Called whenever evidence has been added
	 * @param graphNode de bruijn graph kmer after evidence has been added
	 * @param evidenceNode evidence node that has been added
	 * @param evidence evidence details
	 * @param readKmerOffset kmer offset of this kmer within evidence 
	 * @param kmer evidence kmer
	 */
	protected void onEvidenceAdded(T graphNode, T evidenceNode, VariantEvidence evidence, int readKmerOffset, ReadKmer kmer) { }
	/**
	 * Called whenever evidence has been removed 
	 * @param graphNode de bruijn graph kmer after evidence has been removed
	 * @param evidenceNode evidence that has been removed
	 * @param evidence evidence details
	 * @param readKmerOffset kmer offset of this kmer within evidence 
	 * @param kmer evidence kmer
	 */
	protected void onEvidenceRemoved(T graphNode, T evidenceNode, VariantEvidence evidence, int readKmerOffset, ReadKmer kmer) { }
	private Set<DirectedEvidence> getSupport(List<T> path) {
		Set<DirectedEvidence> support = Sets.newHashSet();
		// iterate in strides to improve support composition when limit is hit 
		int stride = getK();
		for (int j = 0; j < stride; j++) {
			for (int i = j; i < path.size(); i += stride) {
				T node = path.get(i);
				List<DirectedEvidence> nodeSupport = node.getSupportingEvidenceList();
				support.addAll(nodeSupport);
				if (support.size() > SUPPORT_SIZE_HARD_LIMIT) {
					log.warn(String.format("Hit support size hard limit of %d - no longer processing additional support", SUPPORT_SIZE_HARD_LIMIT));
					return support;
				}
			}
		}
		return support;
	}
	private int[] getBaseCountsByCategory(List<T> path, Set<DirectedEvidence> support) {
		int[] baseCounts = new int[] { 0, 0 };
		for (T node : path) {
			for (DirectedEvidence e : node.getSupportingEvidenceList()) {
				baseCounts[((SAMEvidenceSource)e.getEvidenceSource()).sourceCategory()]++;
			}
		}
		// adjust base counts for non-anchored reads
		int[] supportCounts = new int[] { 0, 0 };
		for (DirectedEvidence e : support) {
			supportCounts[((SAMEvidenceSource)e.getEvidenceSource()).sourceCategory()]++;
		}
		// convert kmer counts into base counts
		// Note: this conversion assumes that each piece of evidence forms a single contiguous kmer support path
		for (int i = 0; i < baseCounts.length; i++) {
			baseCounts[i] += (getK() - 1) * supportCounts[i];
		}
		return baseCounts;
	}
	protected AssemblyEvidence createAssembly(List<Long> kmers) {
		List<T> path = new ArrayList<T>(kmers.size());
		List<T> breakendPath = new ArrayList<T>(path.size());
		Integer breakendStartOffset = null;
		int breakendEndOffset = -1;
		for (int i = 0; i < kmers.size(); i++) {
			T node = getKmer(kmers.get(i));
			path.add(node);
			if (!node.isReference()) {
				breakendPath.add(node);
				if (breakendStartOffset == null) {
					breakendStartOffset = i;
				}
				breakendEndOffset = i;
			}
		}
		T beforeBreakend = breakendStartOffset != null ? path.get(breakendStartOffset) : null;
		T afterBreakend = breakendEndOffset >= 0 && breakendEndOffset < path.size() ? path.get(breakendEndOffset) : null;
		
		Set<DirectedEvidence> breakendSupport = getSupport(breakendPath);
		int[] breakendBaseCounts = getBaseCountsByCategory(breakendPath, breakendSupport);
		byte[] bases = getBaseCalls(kmers);
		byte[] quals = getBaseQuals(kmers);
		
		if (beforeBreakend == null || afterBreakend == null) {
			// unanchored
			return AssemblyFactory.createUnanchored(processContext, source, breakendSupport, bases, quals, breakendBaseCounts[0], breakendBaseCounts[1]);
		} else {
			double startBreakendAnchorPosition = DeBruijnNodeBase.getExpectedPositionForDirectAnchor(BreakendDirection.Forward, breakendPath);
			double endBreakendAnchorPosition = DeBruijnNodeBase.getExpectedPositionForDirectAnchor(BreakendDirection.Backward, breakendPath);
			// fall back to expected position of reference kmer
			if (startBreakendAnchorPosition == Double.NaN && beforeBreakend != null) {
				startBreakendAnchorPosition = beforeBreakend.getExpectedPosition();
			}
			if (endBreakendAnchorPosition == Double.NaN && beforeBreakend != null) {
				endBreakendAnchorPosition = afterBreakend.getExpectedPosition();
			}
			assert(endBreakendAnchorPosition != Double.NaN || startBreakendAnchorPosition != Double.NaN);
			if (startBreakendAnchorPosition == Double.NaN) {
				// k=3
				// 1234567890
				// MMMSSSMMM
				// 000   | |
				// |111  | |
				// | 222 | |
				// |  333| |
				// |   444 |
				// |    555|
				// |     666
				// ^ start kmer pos
				//       ^ end kmer pos
				// adjust position from start of kmer over to closest reference anchor base position
				startBreakendAnchorPosition += getK() - 1;
			}
			LinearGenomicCoordinate lgc = processContext.getLinear();
			int startAnchorReferenceIndex = -1;
			int startAnchorPosition =  0;
			int endAnchorReferenceIndex = -1;
			int endAnchorPosition =  0;
			if (startBreakendAnchorPosition != Double.NaN) {
				startBreakendAnchorPosition = Math.round(startAnchorPosition);
				startAnchorReferenceIndex = lgc.getReferenceIndex((long)startBreakendAnchorPosition); 
				startAnchorPosition = lgc.getReferencePosition((long)startBreakendAnchorPosition);
			}
			if (endBreakendAnchorPosition != Double.NaN) {
				endBreakendAnchorPosition = Math.round(endAnchorPosition);
				endAnchorReferenceIndex = lgc.getReferenceIndex((long)endBreakendAnchorPosition); 
				endAnchorPosition = lgc.getReferencePosition((long)endBreakendAnchorPosition);
			}			
			if (endBreakendAnchorPosition == -1) {
				return AssemblyFactory.createAnchored(processContext, source, BreakendDirection.Forward, breakendSupport, startAnchorReferenceIndex, startAnchorPosition, breakendStartOffset, bases, quals, breakendBaseCounts[0], breakendBaseCounts[1]);
			} else if (startBreakendAnchorPosition == -1) {
				return AssemblyFactory.createAnchored(processContext, source, BreakendDirection.Backward, breakendSupport, endAnchorReferenceIndex, endAnchorPosition, path.size() - breakendEndOffset, bases, quals, breakendBaseCounts[0], breakendBaseCounts[1]);
			} else {
				return AssemblyFactory.createAnchored(processContext, source, breakendSupport,
						startAnchorReferenceIndex, startAnchorPosition, breakendStartOffset,
						endAnchorReferenceIndex, endAnchorPosition, path.size() - breakendEndOffset,
						bases, quals, breakendBaseCounts[0], breakendBaseCounts[1]);
			}
		}
	}
}
