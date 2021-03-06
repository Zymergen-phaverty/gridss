#!/bin/bash
# ../scripts/virusbreakend.sh -j ../target/gridss-2.10.0-gridss-jar-with-dependencies.jar -o vbe_out.vcf -r ../../ref/hg19.fa --db ../../virusbreakend/virusbreakenddb ERR093636_virusbreakend_minimal_example.bam
# ../scripts/virusbreakend.sh -j ../target/gridss-2.10.0-gridss-jar-with-dependencies.jar -o vbe_out.vcf -r ../../ref/hg19.fa --db ../../virusbreakend/virusbreakenddb ERR093636_virusbreakend_minimal_example_slower_fastq_input_R1.fq ERR093636_virusbreakend_minimal_example_slower_fastq_input_R2.fq
getopt --test
if [[ ${PIPESTATUS[0]} -ne 4 ]]; then
	echo 'WARNING: "getopt --test"` failed in this environment.' 1>&2
	echo "WARNING: The version of getopt(1) installed on this system might not be compatible with the GRIDSS driver script." 1>&2
fi
set -o errexit -o pipefail -o noclobber -o nounset
last_command=""
current_command=""
trap 'last_command=$current_command; current_command=$BASH_COMMAND' DEBUG
trap 'echo "\"${last_command}\" command completed with exit code $?."' EXIT
#253 forcing C locale for everything
export LC_ALL=C

EX_USAGE=64
EX_NOINPUT=66
EX_CANTCREAT=73
EX_CONFIG=78

kraken2db=""
workingdir="."
reference=""
output_vcf=""
threads=8
kraken2args=""
gridssargs="--jvmheap 13g"
rmargs="--species human"
host=human
nodesdmp=""
virusnbr=""
minreads="50"
metricsrecords=10000000
metricsmaxcoverage=100000
maxcoverage=1000000
force="false"
forceunpairedfastq="false"
USAGE_MESSAGE="
VIRUSBreakend: Viral Integration Recognition Using Single Breakends

Usage: virusbreakend.sh [options] input.bam

	-r/--reference: reference genome of host species.
	-o/--output: output VCF.
	-j/--jar: location of GRIDSS jar
	-t/--threads: number of threads to use. (Default: $threads).
	-w/--workingdir: directory to place intermediate and temporary files. (Default: $workingdir).
	--host: NBCI host filter. Valid values are algae, archaea, bacteria, eukaryotic algae, fungi, human, invertebrates, land plants, plants, protozoa, vertebrates (Default: $host)
	--db: path to virusbreakenddb database directory. Use the supplied virusbreakend-build.sh to build.
	--kraken2args: additional kraken2 arguments
	--gridssargs: additional GRIDSS arguments
	--rmargs: additional RepeatMasker arguments (Default: $rmargs)
	--minreads: minimum number of viral reads perform integration detection (Default: $minreads)
	"
# handled by virusbreakend-build.sh
#--kraken2db: kraken2 database
#--virushostdb: location of virushostdb.tsv. Available from ftp://ftp.genome.jp/pub/db/virushostdb/virushostdb.tsv (Default: {kraken2db}/virushostdb.tsv)
#--nodesdmp: location of NCBI nodes.dmp. Can be downloaded from https://ftp.ncbi.nlm.nih.gov/pub/taxonomy/taxdmp.zip. (Default: {kraken2db}/taxonomy/nodes.dmp)
OPTIONS=ho:t:j:w:r:f
LONGOPTS=help,output:,jar:,threads:,reference:,workingdir:,db:,kraken2db:,kraken2args:,gridssargs:,rmargs:,nodesdmp:,minreads:,force,forceunpairedfastq,host:
! PARSED=$(getopt --options=$OPTIONS --longoptions=$LONGOPTS --name "$0" -- "$@")
if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
    # e.g. return value is 1
    #  then getopt has complained about wrong arguments to stdout
    #  then getopt has complained about wrong arguments to stdout
	echo "$USAGE_MESSAGE" 1>&2
    exit $EX_USAGE
fi
eval set -- "$PARSED"
while true; do
	case "$1" in
		-h|--help)
			echo "$USAGE_MESSAGE" 1>&2
			exit 0
			;;
		-r|--reference)
			reference="$2"
			shift 2
			;;
		-w|--workingdir)
			workingdir="$2"
			shift 2
			;;
		-o|--output)
			output_vcf="$2"
			shift 2
			;;
		--host)
			host="$2"
			shift 2
			;;
		-j|--jar)
			GRIDSS_JAR="$2"
			shift 2
			;;
		-t|--threads)
			printf -v threads '%d\n' "$2" 2>/dev/null
			printf -v threads '%d' "$2" 2>/dev/null
			shift 2
			;;
		--db|--kraken2db)
			kraken2db="$2"
			shift 2
			;;
		--kraken2args)
			kraken2args=$2
			shift 2
			;;
		--gridssargs)
			gridssargs=$2
			shift 2
			;;
		--rmargs)
			rmargs=$2
			shift 2
			;;
		--nodesdmp)
			nodesdmp="$2"
			shift 2
			;;
		--minreads)
			minreads="$2"
			shift 2
			;;
		-f|--force)
			force="true"
			shift 1
			;;
		--forceunpairedfastq)
			forceunpairedfastq="true"
			shift 1
			;;
		--)
			shift
			break
			;;
		*)
			echo "Programming error"
			exit 1
			;;
	esac
done
write_status() { # Before logging initialised
	echo "$(date): $1" 1>&2
}
if [[ "$output_vcf" == "" ]] ; then
	write_status "$USAGE_MESSAGE"
	write_status "Output VCF not specified. Use --output to specify output file."
	exit $EX_USAGE
fi
##### --workingdir
write_status "Using working directory \"$workingdir\""
if [[ "$workingdir" == "" ]] ; then
	$workingdir="$(dirname $output_vcf)"
fi
if [[ "$(tr -d ' 	\n' <<< "$workingdir")" != "$workingdir" ]] ; then
		write_status "workingdir cannot contain whitespace"
		exit $EX_USAGE
	fi
workingdir=$(dirname $workingdir/placeholder)
rootworkingdir=$workingdir
workingdir=$workingdir/$(basename $output_vcf).virusbreakend.working
if [[ ! -d $workingdir ]] ; then
	mkdir -p $workingdir
	if [[ ! -d $workingdir ]] ; then
		write_status "Unable to create $workingdir"
		exit $EX_CANTCREAT
	fi
fi
timestamp=$(date +%Y%m%d_%H%M%S)
# Logging
logfile=$workingdir/virusbreakend.$timestamp.$HOSTNAME.$$.log
# $1 is message to write
write_status() { # After logging initialised
	echo "$(date): $1" | tee -a $logfile 1>&2
}
write_status "Full log file is: $logfile"
# Timing instrumentation
timinglogfile=$workingdir/timing.$timestamp.$HOSTNAME.$$.log
if which /usr/bin/time >/dev/null ; then
	timecmd="/usr/bin/time"
	write_status "Found /usr/bin/time"
else
	timecmd=""
	write_status "Not found /usr/bin/time"
fi
if [[ "$timecmd" != "" ]] ; then
	timecmd="/usr/bin/time --verbose -a -o $timinglogfile"
	if ! $timecmd echo 2>&1 > /dev/null; then
		timecmd="/usr/bin/time -a -o $timinglogfile"
	fi
	if ! $timecmd echo 2>&1 > /dev/null ; then
		timecmd=""
		write_status "Unexpected /usr/bin/time version. Not logging timing information."
	fi
	# We don't need timing info of the echo
	rm -f $timinglogfile
fi
### Find the jars
find_jar() {
	env_name=$1
	if [[ -f "${!env_name:-}" ]] ; then
		echo "${!env_name}"
	else
		write_status "Unable to find $2 jar. Specify using the environment variant $env_name, or the --jar command line parameter."
		exit $EX_NOINPUT
	fi
}
gridss_jar=$(find_jar GRIDSS_JAR gridss)
write_status "Using GRIDSS jar $gridss_jar"
##### --reference
write_status "Using reference genome \"$reference\""
if [[ "$reference" == "" ]] ; then
	write_status "$USAGE_MESSAGE"
	write_status "Reference genome must be specified. Specify using the --reference command line argument"
	exit $EX_USAGE
fi
if [ ! -f $reference ] ; then
	write_status "$USAGE_MESSAGE"
	write_status "Missing reference genome $reference. Specify reference location using the --reference command line argument"
	exit $EX_USAGE
fi
mkdir -p $(dirname $output_vcf)
if [[ ! -d $(dirname $output_vcf) ]] ; then
	write_status "Unable to create directory for $output_vcf for output VCF."
	exit $EX_CANTCREAT
fi
write_status "Using output VCF $output_vcf"
##### --threads
if [[ "$threads" -lt 1 ]] ; then
	write_status "$USAGE_MESSAGE"
	write_status "Illegal thread count: $threads. Specify an integer thread count using the --threads command line argument"
	exit $EX_USAGE
fi
write_status  "Using $threads worker threads."
if [[ "$@" == "" ]] ; then
	write_status  "$USAGE_MESSAGE"
	write_status  "At least one input bam must be specified."
	exit $EX_USAGE
fi
if [[ $force != "true" ]] ; then
	for f in "$@" ; do
		if [[ ! -f $f ]] ; then
			write_status "Input file $f does not exist"
			exit $EX_NOINPUT
		fi
	done
fi
if [[ "$kraken2db" == "" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Missing Kraken2 database location. Specify with --kraken2db"
	exit $EX_USAGE
fi
if [[ ! -d "$kraken2db" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Unable to find kraken2 database directory '$kraken2db'" 
	exit $EX_NOINPUT
fi
if [[ "$nodesdmp" == "" ]] ; then
	nodesdmp="$kraken2db/taxonomy/nodes.dmp"
fi
if [[ "$virusnbr" == "" ]] ; then
	virusnbr="$kraken2db/taxid10239.nbr"
fi
if [[ ! -f "$virusnbr" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Unable to find $virusnbr."
	write_status "Use virusbreakend-build.sh to generate or download from"
	write_status "https://www.ncbi.nlm.nih.gov/genomes/GenomesGroup.cgi?taxid=10239&cmd=download2"
	exit $EX_NOINPUT
fi
if [[ ! -f "$nodesdmp" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Unable to find NCBI nodes.dmp file. Specify with --nodesdmp."
	write_status "kraken2-build will include this file in taxonomy/nodes.dmp if --clean was not run."
	write_status "nodes.dmp can be downloaded from https://ftp.ncbi.nlm.nih.gov/pub/taxonomy/taxdmp.zip"
	exit $EX_NOINPUT
fi
# TODO only virus genomes than can integrate into the host genomes (proviruses)
# HBV: Viruses; Riboviria; Pararnavirae; Artverviricota; [Revtraviricetes]; Blubervirales; Hepadnaviridae; Orthohepadnavirus	
# HIV: Viruses; Riboviria; Pararnavirae; Artverviricota; [Revtraviricetes]; Ortervirales; Retroviridae; Orthoretrovirinae; Lentivirus
# HPV:                       Viruses; Monodnaviria; Shotokuvirae; Cossaviricota; [Papovaviricetes]; Zurhausenvirales; Papillomaviridae; Firstpapillomavirinae; Alphapapillomavirus; Alphapapillomavirus 10
# Merkcel cell polyomavirus: Viruses; Monodnaviria; Shotokuvirae; Cossaviricota; [Papovaviricetes]; Sepolyvirales; Polyomaviridae; Alphapolyomavirus; Human polyomavirus 5
# EBV: Viruses; Duplodnaviria; Heunggongvirae; Peploviricota; Herviviricetes; Herpesvirales; Herpesviridae; Gammaherpesvirinae; Lymphocryptovirus
# HHV-8
# HCV
# HTLV-1
# AAV2
# HHV4
# PCAWG filter: https://www.nature.com/articles/s41588-019-0558-9
# This subset of viruses included: herpesviruses (HHV1, HHV2, HHV4, HHV5, HHV6A/B), simian virus 40 (SV40) and 12 (SV12), human immunodeficiency virus (HIV1), human and simian T-cell lymphotropic virus type 1 (HTLV1 and STLV1), BK polyomavirus (BKP), human parvovirus B19, mouse mammary tumor virus, murine type C retrovirus, Mason–Pfizer monkey virus, HBV, HPV (HPV16, HPV18 and HPV6a) and AAV2. 
if [[ $force != "true" ]] ; then
	for f in "$@" ; do
		if [[ "$(tr -d ' 	\n' <<< "$f")" != "$f" ]] ; then
			write_status "input filenames and paths cannot contain whitespace"
			exit $EX_USAGE
		fi
		write_status "Using input file $f"
	done
fi
# Validate required dependencies exist on PATH
for tool in kraken2 gridss.sh gridss_annotate_vcf_kraken2.sh gridss_annotate_vcf_repeatmasker.sh samtools bcftools java bwa Rscript RepeatMasker ; do
	if ! which $tool >/dev/null; then
		write_status "Error: unable to find $tool on \$PATH"
		exit $EX_CONFIG
	fi
	write_status "Found $(which $tool)"
done
if which gridsstools > /dev/null ; then
	write_status "Found $(which gridsstools)"
	if gridsstools --version > /dev/null ; then
		write_status "gridsstools version: $(gridsstools --version)"
	else
		write_status "gridsstools failure. You will likely need to compile gridsstools from source."
		write_status "Instructions are available at http://github.com/PapenfussLab/gridss/"
	fi
else 
	write_status "MISSING gridsstools. Execution will take 2-3x time longer than when using gridsstools."
	#if [[ "$force" != "true" ]] ; then
	#	write_status "If you really want to continue without gridsstools use --force"
		exit $EX_CONFIG
	#fi
fi

if $(samtools --version-only 2>&1 >/dev/null) ; then
	write_status "samtools version: $(samtools --version-only 2>&1)"
else 
	write_status "Your samtools version is so old it does not support --version-only. Update samtools."
	exit $EX_CONFIG
fi
write_status "R version: $(Rscript --version 2>&1)"
write_status "bwa $(bwa 2>&1 | grep Version || echo -n)"
write_status "$(kraken2 --version | head -1)"
#write_status "minimap2 $(minimap2 --version)"
if which /usr/bin/time >/dev/null ; then
	write_status "time version: $(/usr/bin/time --version 2>&1)"
fi
write_status "bash version: $(/bin/bash --version 2>&1 | head -1)"

# check java version is ok using the gridss.Echo entry point
if java -cp $gridss_jar gridss.Echo ; then
	write_status "java version: $(java -version 2>&1 | tr '\n' '\t')"
else
	write_status "Unable to run GRIDSS jar. Java 1.8 or later is required."
	write_status "java version: $(java -version  2>&1)"
	exit $EX_CONFIG
fi

# Check kraken2 library files
library_arg=""
for fna in $(find $kraken2db -name library.fna) ; do
	if [[ ! -f $fna.fai ]] ; then
		write_status "Indexing $fna (once-off operation)"
		samtools faidx $fna
	fi
	library_arg="$library_arg -KRAKEN_REFERENCES $fna"
done
if [[ "$library_arg" == "" ]] ; then
	write_status "Unable to find any library.fna files in '$kraken2db'."
	write_status "VIRUSbreakend requires the viral kraken2 reference genomes to be retained."
	write_status "Download using \'kraken2-build --download-library viral --db \"$kraken2db\"'"
	write_status "and do not run kraken2-build --clean as it will remove these files."
	exit $EX_NOINPUT
fi

ulimit -n $(ulimit -Hn) # Reduce likelihood of running out of open file handles 
unset DISPLAY # Prevents errors attempting to connecting to an X server when starting the R plotting device


# Hack to support streaming using bash redirection
# "<(cat file.fastq)" notation for fastq
# $1: filename or bash redirection string
function clean_filename {
	# handle file descriptor redirection
	basename "$1" | tr -d ' 	<(:/|$@&%^\\)>'
}

jvm_args=" \
	-Dpicard.useLegacyParser=false \
	-Dsamjdk.use_async_io_read_samtools=true \
	-Dsamjdk.use_async_io_write_samtools=true \
	-Dsamjdk.use_async_io_write_tribble=true \
	-Dsamjdk.buffer_size=4194304 \
	-Dsamjdk.async_io_read_threads=$threads"

prefix_filename=$(basename $output_vcf)
unadjusteddir=$workingdir/unadjusted
adjusteddir=$workingdir/adjusted
prefix_working=$workingdir/$prefix_filename
prefix_adjusted=$adjusteddir/$prefix_filename
prefix_unadjusted=$unadjusteddir/$prefix_filename
file_readname=$prefix_working.readnames.txt
file_report=$prefix_working.kraken2.report.all.txt
file_viral_report=$prefix_working.kraken2.report.viral.txt
file_extracted_report=$prefix_working.kraken2.report.viral.extracted.txt
file_summary_csv=$prefix_working.summary.csv
file_summary_annotated_csv=$prefix_working.summary.ann.csv
exec_concat_fastq=$prefix_working.cat_input_as_fastq.sh
if [[ ! -f $file_readname ]] ; then
	write_status "Identifying viral sequences"
	rm -f $exec_concat_fastq $prefix_working.readnames.txt.tmp
	echo "#!/bin/bash" > $exec_concat_fastq
	for f in "$@" ; do
		cleanf=$(clean_filename "$f")
		echo "gridsstools unmappedSequencesToFastq -@ $threads $f" >> $exec_concat_fastq
	done
	chmod +x $exec_concat_fastq
	{ $timecmd $exec_concat_fastq \
	| kraken2 \
		--threads $threads \
		--db $kraken2db \
		--report $file_report \
		$kraken2args \
		/dev/stdin \
	| java -Xmx512m $jvm_args -cp $gridss_jar gridss.kraken.SubsetToTaxonomy \
		--INPUT /dev/stdin \
		--OUTPUT $file_readname.tmp \
		--FORMAT READ_NAME \
		--NCBI_NODES_DMP $nodesdmp \
	&& mv $file_readname.tmp $file_readname \
	; } 1>&2 2>> $logfile
else
	write_status "Identifying viral sequences	Skipped: found	$file_readname"
fi
if [[ ! -f $file_extracted_report ]] ; then
	taxid_args=""
	if [[ "$host" != "" ]] ; then
		# get the taxid for every contig in $virusnbr
		taxid_args=$(cat $(find $kraken2db -path '**/library/**/*.fna.fai' | grep -v human | grep -v UniVec_Core) \
			| cut -f 1 \
			| grep -F -f <(grep $host $virusnbr | cut -f 1,2 | tr ',\t' '\n\n' | sort -u | sed 's/$/./' | grep -v "^.$") \
			| cut -d '|' -f 2 \
			| sed 's/^/--TAXONOMY_IDS /' \
			| tr '\n' ' ')
		taxid_args="--TAXONOMY_IDS null $taxid_args"
	fi
	write_status "Identifying viruses in sample based on kraken2 summary report"
	# The sort is so we will include any library/added before the default RefSeq sequences (in library/viral)
	kraken_references_arg=$(for fa in $(find $kraken2db -path '**/library/**/*.fna' | sort) ; do echo -n "--KRAKEN_REFERENCES $fa "; done)
	# The OUTPUT redirect is so bcftools doesn't choke on kraken's contig naming convention
	{ $timecmd java -Xmx4g $jvm_args -cp $gridss_jar gridss.kraken.ExtractBestSequencesBasedOnReport \
		--INPUT $file_report \
		--OUTPUT $prefix_working.kraken2.fa \
		--REPORT_OUTPUT $file_viral_report \
		--SUMMARY_REPORT_OUTPUT $file_extracted_report \
		--SUMMARY_OUTPUT $file_summary_csv \
		--NCBI_NODES_DMP $nodesdmp \
		$kraken_references_arg \
		--MIN_SUPPORTING_READS $minreads \
		--TAXONOMIC_DEDUPLICATION_LEVEL Genus \
		$taxid_args \
	; } 1>&2 2>> $logfile
else
	write_status "Identifying viruses	Skipped: found	$file_extracted_report"
fi
if [[ ! -s $prefix_working.kraken2.fa ]] ; then
	write_status "No viral sequences supported by at least $minreads reads."
	rm -f $output_vcf.summary.csv $output_vcf
	file_header=""
	for f in "$@" ; do
		file_header="$file_header	$(clean_filename \"$f\")"
	done
	echo "##fileformat=VCFv4.2" > $output_vcf
	echo "#CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT	$file_header" >> $output_vcf
	touch $output_vcf.summary.csv
	trap - EXIT
	exit 0 # success!
fi
mkdir -p $unadjusteddir
if [[ ! -f $prefix_unadjusted.viral.fa ]] ; then
	tr ':|' '__' < $prefix_working.kraken2.fa > $prefix_unadjusted.viral.fa
fi
for f in "$@" ; do
	infile_filename_prefix=$(clean_filename "$f")
	infile_fq=$workingdir/$infile_filename_prefix.viral.unpaired.fq
	infile_fq1=$workingdir/$infile_filename_prefix.viral.R1.fq
	infile_fq2=$workingdir/$infile_filename_prefix.viral.R2.fq
	if [[ ! -f $infile_fq ]] ; then
		exec_extract_reads=$workingdir/$infile_filename_prefix.extract_reads.sh
		write_status "Extracting viral reads	$f"
		rm -f $exec_extract_reads
		echo "#!/bin/bash" > $exec_extract_reads
		cat >> $exec_extract_reads << EOF
gridsstools extractFragmentsToFastq \
	-@ $threads \
	-r $file_readname \
	-o $infile_fq \
	-1 $infile_fq1 \
	-2 $infile_fq2 \
	$f
EOF
		chmod +x $exec_extract_reads
		{ $timecmd $exec_extract_reads ; } 1>&2 2>> $logfile
	else
		write_status "Extracting viral reads	Skipped: found	$infile_fq"
	fi
done

# $1: input file
# $2: output prefix
function align_fasta() {
	infile_filename_prefix=$(clean_filename "$1")
	infile_fq=$workingdir/$infile_filename_prefix.viral.unpaired.fq
	infile_fq1=$workingdir/$infile_filename_prefix.viral.R1.fq
	infile_fq2=$workingdir/$infile_filename_prefix.viral.R2.fq
	out_dir=$(dirname $2)
	viral_ref=$2.viral.fa
	bam=$out_dir/$infile_filename_prefix.viral.bam
	if [[ ! -f $bam ]] ; then
		if [[ ! -f $viral_ref.bwt ]] ; then
			write_status "Creating index of viral sequences"
			{ $timecmd samtools faidx $viral_ref && bwa index $viral_ref ; } 1>&2 2>> $logfile
		#else
		#	write_status "Creating index of viral sequences	Skipped: found	$viral_ref.bwt"
		fi
		write_status "Aligning viral reads	$bam"
		{ $timecmd cat \
			<(bwa mem -Y -t $threads $viral_ref $infile_fq1 $infile_fq2) \
			<(bwa mem -Y -t $threads $viral_ref $infile_fq | grep -v "^@") \
		| samtools fixmate -m -O BAM - - \
		| samtools sort -@ $threads -T $bam.sorting -o $bam.tmp.bam - \
		&& mv $bam.tmp.bam $bam \
		&& samtools index $bam \
		; } 1>&2 2>> $logfile
		# duplicates are not marked since fragmented insertion sites
		# with a second breakpoint closer than the read length will
		# call all supporting soft-clipped reads
		# e.g. 198T TERT integration in Sung 2012
		# | samtools markdup -O BAM -@ $threads - $infile_virus_bam.tmp.bam \
	else
		write_status "Aligning viral reads	Skipped: found	$bam"
	fi
}
for f in "$@" ; do
	align_fasta "$f" $prefix_unadjusted
done
if [[ ! -f $prefix_unadjusted.merged.bam ]] ; then
	samtools merge $prefix_unadjusted.merged.bam $unadjusteddir/*.viral.bam
fi
mkdir -p $adjusteddir
if [[ ! -f $prefix_adjusted.viral.fa ]] ; then
	write_status "Adjusting reference genome"
	{ $timecmd bcftools mpileup -f $prefix_unadjusted.viral.fa $prefix_unadjusted.merged.bam | bcftools call -c -v --ploidy 1 -V indels -Oz -o $prefix_unadjusted.snps.vcf.gz \
	&& bcftools index $prefix_unadjusted.snps.vcf.gz \
	&& bcftools consensus -f $prefix_unadjusted.viral.fa $prefix_unadjusted.snps.vcf.gz | sed 's/^>/>adjusted_/' > $prefix_adjusted.viral.fa \
	; } 1>&2 2>> $logfile
else
	write_status "Skiping adjusting reference genome: found	$$prefix_adjusted.viral.fa"
fi
for f in "$@" ; do
	align_fasta "$f" $prefix_adjusted
done
if [[ ! -f $prefix_adjusted.merged.bam ]] ; then
	samtools merge $prefix_adjusted.merged.bam $adjusteddir/*.viral.bam
	samtools index $prefix_adjusted.merged.bam
fi
bam_list_args=""
for f in "$@" ; do
	infile_filename_prefix=$(clean_filename "$f")
	bam=$adjusteddir/$infile_filename_prefix.viral.bam
	gridss_dir=$bam.gridss.working
	gridss_prefix=$gridss_dir/$(basename $bam)
	if [[ ! -f $gridss_prefix.insert_size_metrics ]] ; then
		mkdir -p $gridss_dir
		full_gridss_metrics_prefix=$rootworkingdir/$(basename $infile_filename_prefix).gridss.working/$(basename $infile_filename_prefix)
		if [[ -f $full_gridss_metrics_prefix.insert_size_metrics ]] ; then
			write_status "Found existing GRIDSS metrics - copying from 	$(dirname $full_gridss_metrics_prefix)"
			for metric_suffix in cigar_metrics idsv_metrics insert_size_metrics mapq_metrics tag_metrics ; do
				cp $full_gridss_metrics_prefix.$metric_suffix $gridss_prefix.$metric_suffix
			done
		else 
			write_status "Gathering metrics from host alignment	$f"
			# Ideally the metrics on the viral sequence would match the metrics from the host.
			# Unfortunately, this is generally not the case as viral coverage can be very low.
			# To ensure we assemble fragments correctly, we need to grab the host alignment metrics.
			# If GRIDSS has been run, we could use that but we don't want GRIDSS to be an explicit
			# requirement of this pipeline
			# This approach doesn't work for fastq input files.
			exec_extract_host_metrics=$prefix_adjusted.extract_host_metrics.sh
			rm -f $exec_extract_host_metrics
			echo "#!/bin/bash" > $exec_extract_host_metrics
			cat >> $exec_extract_host_metrics << EOF
java -Xmx4g $jvm_args \
	-cp $gridss_jar gridss.analysis.CollectGridssMetrics \
	--INPUT $f \
	--OUTPUT $gridss_prefix \
	--REFERENCE_SEQUENCE $reference \
	--THRESHOLD_COVERAGE $metricsmaxcoverage \
	--TMP_DIR $workingdir \
	--FILE_EXTENSION null \
	--STOP_AFTER $metricsrecords
EOF
			chmod +x $exec_extract_host_metrics
			{ $timecmd $exec_extract_host_metrics; } 1>&2 2>> $logfile
		fi
	else
		write_status "Gathering metrics from host alignment	Skipped: found	$gridss_prefix.insert_size_metrics"
	fi
	bam_list_args="$bam_list_args $bam"
done
# GRIDSS files
file_gridss_vcf=$prefix_adjusted.gridss.vcf
file_gridss_configuration=$prefix_adjusted.gridss.properties
file_assembly=$prefix_adjusted.gridss.assembly.bam
file_host_annotated_vcf=$prefix_adjusted.gridss.bealn.vcf
file_kraken_annotated_vcf=$prefix_adjusted.gridss.bealn.k2.vcf
file_rm_annotated_vcf=$prefix_adjusted.gridss.bealn.k2.rm.vcf
file_filtered_vcf=$prefix_adjusted.gridss.bealn.k2.rm.filtered.vcf
file_wgs_metrics=$prefix_adjusted.wgs_metrics.txt
rm -f $file_gridss_configuration
cat > $file_gridss_configuration << EOF
assembly.downsample.acceptDensityPortion = 5
assembly.downsample.targetEvidenceDensity = 50
assembly.positional.maximumNodeDensity = 100
assembly.positional.safetyModePathCountThreshold = 250000
assembly.positional.safetyModeContigsToCall = 12
EOF
if [[ ! -f $file_gridss_vcf ]] ; then
	write_status "Calling structural variants"
	{ $timecmd gridss.sh \
		-w $adjusteddir \
		-t $threads \
		-r $prefix_adjusted.viral.fa \
		-j $gridss_jar \
		-o $file_gridss_vcf \
		-a $file_assembly \
		-c $file_gridss_configuration \
		--maxcoverage $maxcoverage \
		$gridssargs \
		$bam_list_args \
	; } 1>&2 2>> $logfile
else
	write_status "Calling structural variants	Skipped: found	$file_gridss_vcf"
fi
if [[ ! -f $file_host_annotated_vcf ]] ; then
	# Make sure we have the appropriate indexes for the host reference genome
	{ $timecmd gridss.sh \
		-w $adjusteddir \
		-t $threads \
		-r $reference \
		-j $gridss_jar \
		-s setupreference \
		-a $file_assembly \
		-o placeholder.vcf \
		$gridssargs \
		$bam_list_args \
	; } 1>&2 2>> $logfile
	write_status "Annotating host genome integrations"
	{ $timecmd java -Xmx4g $jvm_args \
			-Dgridss.output_to_temp_file=true \
			-cp $gridss_jar gridss.AnnotateInsertedSequence \
			--TMP_DIR $workingdir \
			--WORKING_DIR $workingdir \
			--REFERENCE_SEQUENCE $reference \
			--WORKER_THREADS $threads \
			--INPUT $file_gridss_vcf \
			--OUTPUT $file_host_annotated_vcf \
	; } 1>&2 2>> $logfile
else
	write_status "Annotating host genome integrations	Skipped: found	$file_host_annotated_vcf"
fi
if [[ ! -f $file_kraken_annotated_vcf ]] ; then
	write_status "Annotating kraken2"
	{ $timecmd gridss_annotate_vcf_kraken2.sh \
		-o $file_kraken_annotated_vcf \
		-j $gridss_jar \
		--kraken2db $kraken2db \
		--threads $threads \
		$kraken2args \
		$file_host_annotated_vcf  \
	; } 1>&2 2>> $logfile
else
	write_status "Annotating kraken2	Skipped: found	$file_kraken_annotated_vcf"
fi
if [[ ! -f $file_rm_annotated_vcf ]] ; then
	write_status "Annotating RepeatMasker"
	{ $timecmd gridss_annotate_vcf_repeatmasker.sh \
		-w $workingdir \
		-o $file_rm_annotated_vcf \
		-j $gridss_jar \
		--threads $threads \
		--rmargs "$rmargs" \
		$kraken2args \
		$file_kraken_annotated_vcf \
	; } 1>&2 2>> $logfile
else
	write_status "Annotating RepeatMasker	Skipped: found	$file_rm_annotated_vcf"
fi
if [[ ! -f $file_filtered_vcf ]] ; then
	write_status "Filtering to host integrations"
	hosttaxid_arg=""
	if [[ "$host" == "human" ]] ; then
		hosttaxid_arg="--TAXONOMY_IDS 9606"
	fi
	{ $timecmd java -Xmx64m $jvm_args -cp $gridss_jar gridss.VirusBreakendFilter \
		--INPUT $file_rm_annotated_vcf \
		--OUTPUT $file_filtered_vcf \
		--REFERENCE_SEQUENCE $reference \
		$hosttaxid_arg \
	; } 1>&2 2>> $logfile
fi
if [[ ! -f $file_summary_annotated_csv ]] ; then
	write_status "Writing annotated summary to $file_summary_annotated_csv"
	rm -f $prefix_adjusted.merged.bam.coverage
	samtools coverage $prefix_adjusted.merged.bam > $prefix_adjusted.merged.bam.coverage
	while read inline; do
		if [[ $inline = taxid_genus* ]] ; then
			echo "$inline	$(head -1 $prefix_adjusted.merged.bam.coverage)	integrations" >> $file_summary_annotated_csv
		else
			taxid=$(echo "$inline" | cut -f 7)
			coverage_stats=$(grep _taxid_${taxid}_ $prefix_adjusted.merged.bam.coverage)
			contig=$(echo "$coverage_stats" | cut -f 1)
			hits=$(grep -E ^adj $file_filtered_vcf | grep -F $contig | wc -l || true)
			echo "$inline	$coverage_stats	$hits"  >> $file_summary_annotated_csv
		fi
	done < $file_summary_csv
fi
cp $file_filtered_vcf $output_vcf
cp $file_summary_annotated_csv $output_vcf.summary.csv

write_status "Generated $output_vcf"
write_status "Done"

trap - EXIT
exit 0 # success!
