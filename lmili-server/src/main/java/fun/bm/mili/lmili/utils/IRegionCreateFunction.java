package fun.bm.mili.lmili.utils;

import abomination.IRegionFile;

import java.io.IOException;

public interface IRegionCreateFunction {
    IRegionFile create(RegionCreatorInfo info) throws IOException;
}