package com.example.bilingualreader.video;

import java.util.List;
import com.example.bilingualreader.contract.model.AppException;

/** All acquisition implementations must return original text and actual media timestamps. */
public interface TimedTextSource<I> {
  List<SubtitleSegment> acquire(I input) throws AppException;
}
