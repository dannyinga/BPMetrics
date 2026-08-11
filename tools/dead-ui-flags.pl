#!/usr/bin/perl
use strict;
use warnings;

# Finds Compose state that is written but never read.
#
# The failure this exists for: a button sets `showThing = true`, the dialog it was meant to open
# is never rendered, and the button silently does nothing. It compiles, it warns about nothing,
# and no unit test can see it — the export button on four detail pages was dead for two commits
# this way. See §10.1 of "Taxonomy Consolidation".
#
# Usage:
#   perl tools/dead-ui-flags.pl $(find mobile/src/main/java -name '*.kt')
#
# Reports each `var x by remember { mutableStateOf(...) }` whose only appearances are assignments.
# Comments are stripped first so a name mentioned only in prose does not count as a read.

my $found = 0;

for my $file (@ARGV) {
    open my $fh, '<', $file or next;
    local $/;
    my $src = <$fh>;
    close $fh;

    $src =~ s{//[^\n]*}{}g;

    my %declared;
    while ($src =~ /\bvar\s+(\w+)\s+by\s+remember[^\n]*mutableStateOf/g) {
        $declared{$1} = 1;
    }

    for my $name (sort keys %declared) {
        # `=` not followed by `=`, so `x == y` counts as a read rather than a write.
        my $writes = () = $src =~ /\b\Q$name\E\s*=[^=]/g;
        my $all    = () = $src =~ /\b\Q$name\E\b/g;
        my $reads  = $all - $writes - 1;    # less the declaration itself

        if ($reads <= 0) {
            printf "%s: %s is written %d time(s) and never read\n", $file, $name, $writes;
            $found++;
        }
    }
}

print "No write-only UI state.\n" unless $found;
exit($found ? 1 : 0);
