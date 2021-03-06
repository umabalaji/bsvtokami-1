//
// Created by Jamey Hicks on 1/10/20.
//

#pragma once

class SourcePos {
public:
    const string sourceName;
    const int line;
    const int positionInLine;

    SourcePos() : sourceName(), line(0), positionInLine(0) {}

    SourcePos(const string &sourceName, int line, int positionInLine) : sourceName(sourceName), line(line),
                                                                        positionInLine(positionInLine) {}

    string toString() const {
        return sourceName + ":" + to_string(line);
    }
};
