package scanner

import "testing"

func TestParse(t *testing.T) {
	tests := []struct {
		in   string
		want Parsed
	}{
		// Movies: common scene/P2P names.
		{"The.Matrix.1999.1080p.BluRay.x264-SPARKS.mkv", Parsed{Title: "The Matrix", Year: 1999}},
		{"Inception (2010) [1080p] [BluRay] [5.1] [YTS.MX].mp4", Parsed{Title: "Inception", Year: 2010}},
		{"Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv", Parsed{Title: "Dune Part Two", Year: 2024}},
		{"Parasite.2019.KOREAN.1080p.BluRay.x264.DTS-FGT.mkv", Parsed{Title: "Parasite", Year: 2019}},
		{"interstellar_2014_720p_brrip.avi", Parsed{Title: "Interstellar", Year: 2014}},
		{"Mad Max - Fury Road (2015).mkv", Parsed{Title: "Mad Max - Fury Road", Year: 2015}},
		{"Spider-Man.Into.the.Spider-Verse.2018.1080p.mkv", Parsed{Title: "Spider-Man Into the Spider-Verse", Year: 2018}},
		{"Amelie.2001.FRENCH.720p.BluRay.x264.mkv", Parsed{Title: "Amelie", Year: 2001}},
		{"Cidade.de.Deus.2002.1080p.BluRay.x264.DUAL.mkv", Parsed{Title: "Cidade de Deus", Year: 2002}},
		{"Tropa de Elite 2 (2010) Nacional 1080p.mp4", Parsed{Title: "Tropa de Elite 2", Year: 2010}},
		{"[YTS.MX] Whiplash (2014) [1080p].mp4", Parsed{Title: "Whiplash", Year: 2014}},
		{"www.Torrenting.com - Oppenheimer.2023.1080p.WEBRip.mkv", Parsed{Title: "Oppenheimer", Year: 2023}},
		{"The.Lord.of.the.Rings.The.Return.of.the.King.2003.EXTENDED.1080p.BluRay.mkv", Parsed{Title: "The Lord of the Rings The Return of the King", Year: 2003}},
		{"Alien.Directors.Cut.1979.1080p.mkv", Parsed{Title: "Alien", Year: 1979}},
		{"Movie Without Year.mkv", Parsed{Title: "Movie Without Year"}},
		{"Some.Movie.720p.HDTV.x264.mkv", Parsed{Title: "Some Movie"}},
		{"heat.mkv", Parsed{Title: "Heat"}},

		// Years that are part of the title.
		{"1917.2019.1080p.BluRay.mkv", Parsed{Title: "1917", Year: 2019}},
		{"1917.1080p.mkv", Parsed{Title: "1917"}},
		{"2012.2009.720p.mkv", Parsed{Title: "2012", Year: 2009}},
		{"2001.A.Space.Odyssey.1968.REMASTERED.1080p.mkv", Parsed{Title: "2001 A Space Odyssey", Year: 1968}},
		{"Blade.Runner.2049.2017.2160p.mkv", Parsed{Title: "Blade Runner 2049", Year: 2017}},
		{"Blade.Runner.2049.1080p.mkv", Parsed{Title: "Blade Runner 2049"}},
		{"Space.1999.S01E01.mkv", Parsed{Title: "Space", Year: 1999, Season: 1, Episode: 1}},

		// TV episodes.
		{"Breaking.Bad.S01E02.720p.BluRay.x264.mkv", Parsed{Title: "Breaking Bad", Season: 1, Episode: 2}},
		{"breaking.bad.s05e14.1080p.mkv", Parsed{Title: "Breaking Bad", Season: 5, Episode: 14}},
		{"Game of Thrones - S08E03 - The Long Night.mkv", Parsed{Title: "Game of Thrones", Season: 8, Episode: 3}},
		{"The.Office.US.S02E01.mkv", Parsed{Title: "The Office US", Season: 2, Episode: 1}},
		{"Doctor.Who.2005.S01E01.Rose.mkv", Parsed{Title: "Doctor Who", Year: 2005, Season: 1, Episode: 1}},
		{"Friends.S01E01E02.mkv", Parsed{Title: "Friends", Season: 1, Episode: 1, EpisodeEnd: 2}},
		{"Friends.S01E16-E17.mkv", Parsed{Title: "Friends", Season: 1, Episode: 16, EpisodeEnd: 17}},
		{"Friends.S01E05-06.mkv", Parsed{Title: "Friends", Season: 1, Episode: 5, EpisodeEnd: 6}},
		{"Friends S01E05 720 x264.mkv", Parsed{Title: "Friends", Season: 1, Episode: 5}},
		{"Seinfeld 3x05 The Pen.avi", Parsed{Title: "Seinfeld", Season: 3, Episode: 5}},
		{"Lost Season 1 Episode 4.mp4", Parsed{Title: "Lost", Season: 1, Episode: 4}},
		{"Dark S01 E03.mkv", Parsed{Title: "Dark", Season: 1, Episode: 3}},
		{"One.Piece.S01E1071.1080p.mkv", Parsed{Title: "One Piece", Season: 1, Episode: 1071}},
		{"[HorribleSubs] Attack on Titan S03E01 [1080p].mkv", Parsed{Title: "Attack on Titan", Season: 3, Episode: 1}},
		{"S01E05.mkv", Parsed{Season: 1, Episode: 5}},
		{"s02e10 - The Finale.mp4", Parsed{Season: 2, Episode: 10}},
		{"Marvels.Agents.of.S.H.I.E.L.D.S01E01.mkv", Parsed{Title: "Marvels Agents of SHIELD", Season: 1, Episode: 1}},
		{"L.A.Confidential.1997.1080p.mkv", Parsed{Title: "LA Confidential", Year: 1997}},
		{"A Star Is Born (2018).mkv", Parsed{Title: "A Star Is Born", Year: 2018}},

		// Concerts and live shows.
		{"Metallica - S&M2 (2020) 1080p.mkv", Parsed{Title: "Metallica - S&M2", Year: 2020}},
		{"Queen.Live.at.Wembley.Stadium.1986.DVDRip.avi", Parsed{Title: "Queen Live at Wembley Stadium", Year: 1986}},
		{"Pink Floyd - Pulse (1995) [BDRip 1080p].mkv", Parsed{Title: "Pink Floyd - Pulse", Year: 1995}},
		{"Rush.R30.Live.In.Frankfurt.2005.mkv", Parsed{Title: "Rush R30 Live In Frankfurt", Year: 2005}},

		// Real names from the MIBOX3 library (2026-11-24 crawl).
		{"Pink Floyd P. U. L. S. E. Live At Earls Court (1994)", Parsed{Title: "Pink Floyd PULSE Live At Earls Court", Year: 1994}},
		{"Fatal Attraction (1987) (1080p BluRay x265 HEVC 10bit 5.1)[Bandi]", Parsed{Title: "Fatal Attraction", Year: 1987}},
		{"Bon.Jovi.Live.The.Crush.Tour.2000.DVDRip.XviD-GIGOTO.avi", Parsed{Title: "Bon Jovi Live The Crush Tour", Year: 2000}},
		{"history.of.the.eagles.2013.part.one.1080p.bluray.dts.x264-publichd.mkv", Parsed{Title: "History Of The Eagles", Year: 2013}},
		{"Friends.the.Reunion.2021.1080p.HMAX.WEB-DL.DD5.1.x264-CMRG.mkv", Parsed{Title: "Friends the Reunion", Year: 2021}},
		{"Green Day - Live at Rock in Rio 2022", Parsed{Title: "Green Day - Live at Rock in Rio", Year: 2022}},
		{"The Warning - Live at Auditorio Nacional, CDMX (2025)", Parsed{Title: "The Warning - Live at Auditorio Nacional, CDMX", Year: 2025}},

		// Folder names (no extension).
		{"The Godfather (1972)", Parsed{Title: "The Godfather", Year: 1972}},
		{"Breaking Bad", Parsed{Title: "Breaking Bad"}},
	}

	for _, tt := range tests {
		t.Run(tt.in, func(t *testing.T) {
			got := Parse(tt.in)
			if got != tt.want {
				t.Errorf("Parse(%q)\n got  %+v\n want %+v", tt.in, got, tt.want)
			}
		})
	}
}

func TestParseSeasonFolder(t *testing.T) {
	tests := []struct {
		in     string
		season int
		ok     bool
	}{
		{"Season 1", 1, true},
		{"season.02", 2, true},
		{"S03", 3, true},
		{"Temporada 4", 4, true},
		{"Series 5", 5, true},
		{"Volume 1", 1, true},
		{"Vol 2", 2, true},
		{"Breaking Bad", 0, false},
		{"Specials", 0, false},
	}
	for _, tt := range tests {
		s, ok := ParseSeasonFolder(tt.in)
		if s != tt.season || ok != tt.ok {
			t.Errorf("ParseSeasonFolder(%q) = %d,%v want %d,%v", tt.in, s, ok, tt.season, tt.ok)
		}
	}
}

func TestNormalizeKey(t *testing.T) {
	tests := map[string]string{
		"Breaking Bad":       "breaking bad",
		"breaking.bad":       "breaking bad",
		"Breaking-Bad!":      "breaking bad",
		"Law & Order":        "law and order",
		"  Spaces   Galore ": "spaces galore",
		"Amélie":             "amélie",
	}
	for in, want := range tests {
		if got := NormalizeKey(in); got != want {
			t.Errorf("NormalizeKey(%q) = %q want %q", in, got, want)
		}
	}
}

func TestIsVideo(t *testing.T) {
	for _, n := range []string{"a.mkv", "a.MP4", "a.ts", "a.webm", "a.m4v", "a.mov", "a.avi"} {
		if !IsVideo(n) {
			t.Errorf("IsVideo(%q) = false", n)
		}
	}
	for _, n := range []string{"a.srt", "a.nfo", "a.jpg", "mkv", "a.mkv.part"} {
		if IsVideo(n) {
			t.Errorf("IsVideo(%q) = true", n)
		}
	}
}
