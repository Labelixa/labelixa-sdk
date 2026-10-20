package labelixa

// WIRE VOCABULARY: the API's own field names that this client reads by
// name, in one file.
//
// Some fields of the Labelixa API are named in Turkish. They are PROTOCOL:
// renaming them here would not translate anything, it would simply stop
// reading the server's answer. So they are not translated, and they are
// not hidden either.
//
// They live in this single file so the publish guard can exempt exactly
// one file from the "no Turkish words" rule instead of losing that rule
// across the whole module. The guard still checks this file for ticket
// ids, non-English characters and audit markers.
//
// Reference: https://labelixa.com/docs/api

// errorField names the field of an error answer (any non-2xx) that
// carries the server's sentence: {"hata": "..."}.
const errorField = "hata"
