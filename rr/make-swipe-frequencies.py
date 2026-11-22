#!/usr/bin/env python3
"""
Creates the word frequencies used by the built-in swipe typing: app/src/main/assets/swipe/<locale>.tsv.gz

The frequencies in the bundled dictionaries are fine for ordering suggestions while tapping, but some
common words have far too low values (e.g. Polish "bym"), and rare proper nouns are often as frequent
as common words. For swipe typing the frequency decides between words with similar paths, so this
creates a corrected list from the dictionary words and the word frequencies of the wordfreq project
(pip install wordfreq).

Scale: like the dictionaries 1..254, but one step always means 15% more probable
(see SwipeDecoder.FREQUENCY_LOG_STEP). Words unknown to wordfreq and names get lower values.

Usage: rr/make-swipe-frequencies.py pl en-US
"""
import collections, gzip, math, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from readdict import read_dict
from wordfreq import zipf_frequency

STEP = math.log(1.15)
UNKNOWN_MAX_ZIPF = 1.5     # words wordfreq doesn't know are rare
UNKNOWN_PENALTY = 0.3
NAME_PENALTY = 0.5         # capitalized words, mostly names


def to_frequency(zipf):
    return max(1, min(254, round(255 + math.log(10 ** (zipf - 9)) / STEP)))


def main(locales):
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets')
    os.makedirs(os.path.join(root, 'swipe'), exist_ok=True)
    for locale in locales:
        language = locale.split('-')[0]
        _, words = read_dict(os.path.join(root, 'dicts', f'main_{locale}.dict'))
        # typical wordfreq value for a dictionary frequency, for estimating unknown words
        by_dict_frequency = collections.defaultdict(list)
        zipfs = {}
        for word, frequency, offensive in words:
            z = zipf_frequency(word.lower(), language)
            zipfs[word] = z
            if z > 0:
                by_dict_frequency[frequency // 10].append(z)
        median = {k: sorted(v)[len(v) // 2] for k, v in by_dict_frequency.items()}
        out = []
        for word, frequency, offensive in words:
            z = zipfs[word]
            if z <= 0:
                z = min(median.get(frequency // 10, UNKNOWN_MAX_ZIPF), UNKNOWN_MAX_ZIPF) - UNKNOWN_PENALTY
            if word[:1].isupper():
                z -= NAME_PENALTY
            out.append(f'{word}\t{to_frequency(z)}\n')
        path = os.path.join(root, 'swipe', f'{locale}.tsv.gz')
        with gzip.GzipFile(path, 'wb', mtime=0) as f:
            f.write(''.join(out).encode('utf-8'))
        print(locale, len(out), 'words,', os.path.getsize(path), 'bytes')


if __name__ == '__main__':
    main(sys.argv[1:] or ['pl', 'en-US'])
