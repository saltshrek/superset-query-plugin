"""Outlines SQL files for code-source, using sqlglot.

code-source runs this once per refresh. The contract between the two is in
docs/features/007-file-outlines.md ("Contract between code-source and the
analyzer"):

- stdin: {"dialect": "trino" | null, "files": [{"path": ..., "text": ...}]}
- stdout: one JSON object per line, per file, in input order:
  {"version": 1, "path": ..., "outline": {...}, "problems": [...]}
- stderr: diagnostics only. Exit 0 once every file has its line.

Each outline is in the API's shape (docs/api.md), so code-source passes it on
as it is.

Positions come from tokens and identifiers, which carry character offsets.
Most other parse-tree nodes don't, so ranges, and which CTE or query a
keyword, function or table belongs to, are all worked out from offsets.
Comments aren't tokens: they're found in the gaps between tokens, where
strings and code can't be.
"""

import bisect
import json
import re
import sys

import sqlglot
from sqlglot import exp
from sqlglot.dialects.dialect import Dialect
from sqlglot.errors import SqlglotError
from sqlglot.tokens import TokenType

VERSION = 1

# Keywords found in nearly every query, which say nothing about a file.
UBIQUITOUS = {
    "AND", "AS", "ASC", "DESC", "ELSE", "END", "FROM", "IN", "IS", "NOT",
    "NULL", "ON", "OR", "SELECT", "THEN", "WHEN", "WHERE",
}

# Keywords the tokenizer splits that mean something else as one. Oracle's
# START WITH would otherwise list WITH, as though there were a CTE.
COMPOUND_KEYWORDS = {("START", "WITH"): "START WITH"}

# Keywords that can be followed by "(" without being a function call.
NOT_FUNCTIONS = {"AS", "EXISTS", "FILTER", "GROUP", "IN", "OVER", "USING", "VALUES"}

NOT_KEYWORDS = {
    TokenType.VAR, TokenType.IDENTIFIER, TokenType.STRING, TokenType.NUMBER,
    TokenType.NATIONAL_STRING, TokenType.RAW_STRING, TokenType.BIT_STRING,
    TokenType.HEX_STRING, TokenType.BYTE_STRING, TokenType.HEREDOC_STRING,
    TokenType.UNICODE_STRING, TokenType.PARAMETER,
}

WORDS = re.compile(r"[A-Z_][A-Z0-9_ ]*")
JINJA_EXPRESSION = re.compile(r"\{\{.*?\}\}", re.S)
JINJA_STATEMENT = re.compile(r"\{%.*?%\}|\{#.*?#\}", re.S)
PLACEHOLDER = re.compile(r"_+")
COMMENT = re.compile(r"--[^\n]*|/\*.*?\*/", re.S)


def mask_jinja(text):
    """Hides Superset's Jinja from the parser, keeping every offset and line.

    An expression may stand for a value or a name, so it becomes a run of
    underscores, which parses as either. Statements and comments become
    whitespace.
    """
    text = JINJA_STATEMENT.sub(lambda m: re.sub(r"[^\n]", " ", m.group()), text)
    return JINJA_EXPRESSION.sub(lambda m: re.sub(r"[^\n]", "_", m.group()), text)


class Lines:
    def __init__(self, text):
        self.starts = [0] + [i + 1 for i, c in enumerate(text) if c == "\n"]

    def of(self, offset):
        """The 1-based line of character [offset]."""
        return bisect.bisect_right(self.starts, offset)

    def column(self, offset):
        """The 1-based column of character [offset] on its line, in code points."""
        return offset - self.starts[self.of(offset) - 1] + 1


class Block:
    """Consecutive comment lines, with nothing else on them."""

    def __init__(self, start, end, start_line, end_line, lines):
        self.start, self.end, self.start_line, self.end_line, self.lines = start, end, start_line, end_line, lines

    @property
    def text(self):
        """The description it gives: wrapped lines joined, paragraphs kept apart."""
        paragraphs, current = [], []
        for line in self.lines + [""]:
            if line:
                current.append(line)
            elif current:
                paragraphs.append(" ".join(current))
                current = []
        return "\n\n".join(paragraphs) or None


def comment_blocks(text, tokens, lines):
    """The comment blocks in [text], in order. Comments after code on the same
    line aren't part of any block."""
    gaps, previous_end = [], -1
    for token in tokens:
        gaps.append((previous_end + 1, token.start))
        previous_end = token.end
    gaps.append((previous_end + 1, len(text)))

    blocks = []
    for gap_start, gap_end in gaps:
        for match in COMMENT.finditer(text, gap_start, gap_end):
            line_start = text.rfind("\n", 0, match.start()) + 1
            if text[line_start:match.start()].strip():
                continue
            start_line, end_line = lines.of(match.start()), lines.of(match.end() - 1)
            content = comment_lines(match.group())
            last = blocks[-1] if blocks else None
            if last and start_line == last.end_line + 1:
                last.end, last.end_line, last.lines = match.end() - 1, end_line, last.lines + content
            else:
                blocks.append(Block(match.start(), match.end() - 1, start_line, end_line, content))
    return blocks


def comment_lines(comment):
    """A comment's lines, without markers or surrounding space."""
    if comment.startswith("--"):
        return [comment[2:].strip()]
    body = comment[2:-2].splitlines()
    return [line.strip().lstrip("*").strip() for line in body]


class Part:
    """A query or a CTE, and what it uses directly."""

    def __init__(self, start, end, name=None):
        self.start, self.end, self.name = start, end, name
        # The comment block that describes it, if any.
        self.described_by = None
        self.keywords, self.functions, self.tables, self.uses = set(), set(), set(), set()

    def contains(self, offset):
        return self.start <= offset <= self.end

    def to_json(self, lines):
        out = {"name": self.name} if self.name is not None else {}
        out["range"] = {
            "start": lines.of(self.start),
            "end": lines.of(self.end),
            "startColumn": lines.column(self.start),
            "endColumn": lines.column(self.end),
        }
        text = self.described_by.text if self.described_by else None
        out["description"] = text
        out["descriptionRange"] = (
            {"start": self.described_by.start_line, "end": self.described_by.end_line} if text else None
        )
        out["keywords"] = sorted(self.keywords)
        out["functions"] = sorted(self.functions)
        out["tables"] = sorted(self.tables)
        out["uses"] = sorted(self.uses)
        return out


class Query(Part):
    def __init__(self, start, end):
        super().__init__(start, end)
        self.ctes = []
        self.recursive = False

    def owner(self, offset):
        """The CTE at [offset], or else this query."""
        return next((cte for cte in self.ctes if cte.contains(offset)), self)

    def to_json(self, lines):
        out = super().to_json(lines)
        out["recursive"] = self.recursive
        out["ctes"] = [cte.to_json(lines) for cte in self.ctes]
        return out


class Analyzer:
    def __init__(self, dialect):
        self.dialect = dialect
        self.keywords = set(dialect.tokenizer_class.KEYWORDS)
        parser = dialect.parser_class
        self.known_functions = set(parser.FUNCTIONS) | set(parser.FUNCTION_PARSERS)

    def outline(self, text):
        """The outline of [text], and any problems that made it partial."""
        problems = []
        keywords, functions, queries = set(), set(), []
        masked = mask_jinja(text)
        lines = Lines(masked)
        try:
            tokens = self.dialect.tokenize(masked)
        except SqlglotError as e:
            tokens = []
            problems.append(f"tokenizing: {e}")

        blocks = comment_blocks(masked, tokens, lines)
        for chunk, previous_end in statements(tokens):
            try:
                parsed = [s for s in self.dialect.parser().parse(chunk, masked) if s is not None]
            except SqlglotError as e:
                parsed = []
                problems.append(f"line {lines.of(chunk[0].start)}: {first_line(e)}")
            identifiers = {i.meta["start"] for s in parsed for i in s.find_all(exp.Identifier) if "start" in i.meta}

            query = None
            if len(parsed) == 1 and isinstance(parsed[0], exp.Query):
                query = self.query(parsed[0], chunk)
                describe(query, [b for b in blocks if previous_end < b.start and b.end < query.end], lines)
                queries.append(query)

            skip = False
            for i, token in enumerate(chunk):
                if skip or token.start in identifiers:
                    skip = False
                    continue
                if (name := self.function_at(chunk, i)) is not None:
                    functions.add(name)
                    if query:
                        query.owner(token.start).functions.add(name)
                elif (word := self.keyword(token)) is not None:
                    if i + 1 < len(chunk) and (word, self.keyword(chunk[i + 1])) in COMPOUND_KEYWORDS:
                        word, skip = COMPOUND_KEYWORDS[(word, self.keyword(chunk[i + 1]))], True
                    keywords.add(word)
                    if query:
                        query.owner(token.start).keywords.add(word)

        references = set()
        for query in queries:
            for part in [query, *query.ctes]:
                references |= part.tables

        outline = {
            "language": "sql",
            "partial": bool(problems),
            "keywords": sorted(keywords),
            "functions": sorted(functions),
            "references": sorted(references),
            "sql": {"queries": [q.to_json(lines) for q in queries]},
        }
        return outline, problems

    def query(self, statement, chunk):
        query = Query(chunk[0].start, chunk[-1].end)
        with_ = statement.args.get("with_")
        query.recursive = bool(with_ and with_.args.get("recursive"))
        for cte in with_.expressions if with_ else []:
            name = cte.args["alias"].this
            end = cte_end(chunk, name.meta["start"])
            query.ctes.append(Part(name.meta["start"], end, name.name))
        names = {cte.name.lower(): cte for cte in query.ctes}

        for table in statement.find_all(exp.Table):
            if not table.name or PLACEHOLDER.fullmatch(table.name):
                continue
            start = table.this.meta.get("start") if isinstance(table.this, exp.Identifier) else None
            owner = query.owner(start) if start is not None else query
            definer = defining_with(table)
            if definer is with_ and table.name.lower() in names:
                owner.uses.add(names[table.name.lower()].name)
            elif definer is None:
                owner.tables.add(".".join(part.sql(dialect=self.dialect) for part in table.parts))
        return query

    def function_at(self, tokens, i):
        """The name of the function called at tokens[i], if it's a call."""
        if i + 1 >= len(tokens) or tokens[i + 1].token_type != TokenType.L_PAREN:
            return None
        token = tokens[i]
        name = token.text.upper()
        if name in NOT_FUNCTIONS or PLACEHOLDER.fullmatch(name):
            return None
        if token.token_type == TokenType.VAR or name in self.known_functions:
            return name
        return None

    def keyword(self, token):
        if token.token_type in NOT_KEYWORDS:
            return None
        word = " ".join(token.text.upper().split())
        if word not in self.keywords or word in UBIQUITOUS or not WORDS.fullmatch(word):
            return None
        return word


def statements(tokens):
    """[tokens] split into statements at semicolons, leaving out empty ones.
    Each comes with the offset where the token before it ended (-1 for none)."""
    chunk, previous_end, chunk_previous_end = [], -1, -1
    for token in tokens:
        if token.token_type == TokenType.SEMICOLON:
            if chunk:
                yield chunk, chunk_previous_end
            chunk = []
        else:
            if not chunk:
                chunk_previous_end = previous_end
            chunk.append(token)
        previous_end = token.end
    if chunk:
        yield chunk, chunk_previous_end


def describe(query, blocks, lines):
    """Gives [query] and its CTEs their descriptions from [blocks], the comment
    blocks between the previous statement and the query's end.

    A block directly above the line a CTE's name is on describes the CTE. The
    query's is the nearest block above it that doesn't describe a CTE.
    """
    above = [b for b in blocks if b.end < query.start]
    first_line = lines.of(query.start)
    nearest = above[-1] if above else None
    first = query.ctes[0] if query.ctes else None
    if first and nearest and lines.of(first.start) == first_line and nearest.end_line == first_line - 1:
        first.described_by = nearest
        above = above[:-1]
    if above:
        query.described_by = above[-1]

    inside = [b for b in blocks if b.start > query.start]
    for cte in query.ctes:
        name_line = lines.of(cte.start)
        if name_line == first_line:
            continue
        cte.described_by = next((b for b in inside if b.end < cte.start and b.end_line == name_line - 1), cte.described_by)


def cte_end(tokens, name_start):
    """The offset where the CTE named at [name_start] ends: its closing ")"."""
    i = next(i for i, t in enumerate(tokens) if t.start == name_start)
    depth = 0
    # Past any column list, to the AS at the same depth, then its "(".
    while not (depth == 0 and tokens[i].token_type == TokenType.ALIAS):
        depth += {TokenType.L_PAREN: 1, TokenType.R_PAREN: -1}.get(tokens[i].token_type, 0)
        i += 1
    while tokens[i].token_type != TokenType.L_PAREN:
        i += 1
    depth = 0
    while True:
        depth += {TokenType.L_PAREN: 1, TokenType.R_PAREN: -1}.get(tokens[i].token_type, 0)
        if depth == 0:
            return tokens[i].end
        i += 1


def defining_with(table):
    """The WITH clause that defines [table] as a CTE, or None if it's a real table."""
    if table.args.get("db") or table.args.get("catalog"):
        return None
    name = table.name.lower()
    node = table.parent
    while node is not None:
        with_ = node.args.get("with_") if isinstance(node, exp.Expression) else None
        if with_ and any(cte.alias_or_name.lower() == name for cte in with_.expressions):
            return with_
        node = node.parent
    return None


def first_line(error):
    return str(error).splitlines()[0] if str(error) else type(error).__name__


def main():
    request = json.load(sys.stdin)
    try:
        dialect = Dialect.get_or_raise(request.get("dialect"))
    except ValueError as e:
        print(f"Unknown SQL dialect: {e}", file=sys.stderr)
        return 2
    analyzer = Analyzer(dialect)
    for file in request["files"]:
        try:
            outline, problems = analyzer.outline(file["text"])
        except Exception as e:  # One file's failure mustn't stop the others.
            outline, problems = None, [f"analyzer error: {type(e).__name__}: {e}"]
        line = {"version": VERSION, "path": file["path"], "outline": outline, "problems": problems}
        sys.stdout.write(json.dumps(line) + "\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
